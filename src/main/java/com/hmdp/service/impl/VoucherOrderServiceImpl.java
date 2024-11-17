package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

//    在java中共有三部分来存放数据，分别为栈堆和元空间
//    成员变量存放在堆中(类被实例化之后他们的值并没有发生共享而是每次进行初始化)，而局部变量存放在栈中
//    由static关键字修饰的变量(不能在方法中)他们属于类，并不属于对应的示例他们存放在元空间中
    @Autowired
    private SeckillVoucherServiceImpl seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisWorker redisWorker;

    @Autowired
    private RedissonClient redissonClient;

//    private static final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<VoucherOrder>(1024 * 1024);

    //当想创建类内共享的资源时，使用static修饰
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static{
        //写到静态代码块中更加清楚
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /*private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    //从消息队列中获取订单
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);

                    //ack确认
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }*/
    private static final String queueName = "stream.orders";
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try{
                    //这里添加了堵塞，每两秒从队列中读取一次数据
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    if(list == null || list.isEmpty()) continue;

                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);
                    //ack确认从pending-list中移除消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",entries.getId());
                }catch (Exception e){
                    //处理消息时出现异常，需要在pending-list中进行处理
                    handlePendingList();
                    log.error("An error occurred",e);
                }

            }
        }
        private void handlePendingList(){
            while(true) {
                try {
                    //这里添加了堵塞，每两秒从队列中读取一次数据
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    if (list == null || list.isEmpty()) break;

                    MapRecord<String, Object, Object> entries = list.get(0);
                    Map<Object, Object> values = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    handleVoucherOrder(voucherOrder);
                    //ack确认从pending-list中移除消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", entries.getId());
                } catch (Exception e) {
                    //处理消息时出现异常，需要在pending-list中进行处理
                    log.error("An error occurred",e);
                }
            }
        }

    }

    @PostConstruct
    private void init(){
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //resource和autowired的区别：
    //resource注入首先会根据注入的名称查找对应的bean，之后再根据类名来找
    //autowired则会先去优先找对应的类名

    //更新数据库时可能会出现，两个线程查询到的数据是一样的，但是有一个快一个慢，导致他们都会对库存进行减一
    //也就是线程安全问题：多个线程对同一数据进行操作，可能会导致数据错误。
    //可以加入乐观锁(有cas法和版本号法)

//  @Transactional 保证数据库的一致性所以使用事务注解，数据库数据回滚
/*
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {

        long orderId = redisWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();
        Long status = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString()
        );
        int r = status.intValue();

        if(r != 0){
            //表示库存不足或者用户已经下单
            return Result.fail(r == 1 ? "用户已经下单" : "库存不足");
        }

        //组成一个订单放到消息队列中去，让另外一个线程去处理
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
*/

        /*根据id查询秒杀优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);

        if(seckillVoucher == null) return Result.fail("优惠券不存在");
        if(LocalDateTime.now().isAfter(seckillVoucher.getEndTime()))
            return Result.fail("优惠券不在售卖时间");

        if(LocalDateTime.now().isBefore(seckillVoucher.getBeginTime()))
            return Result.fail("优惠券不在售卖时间");

        //优惠券存在，判断库存
        if(seckillVoucher.getStock() < 1)
            return Result.fail("库存不足");

        //这里虽然使用乐观锁实现了防止高并发导致的线程安全问题：库存超卖
        //但是并没有限制用户一人只能买一单

        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean iSLock = lock.tryLock();
        //这里假如不是分布式服务，可使用java中自带的悲观锁(粒度控制在用户id)
        //这里不能满足可重入的特性
        if(!iSLock) return Result.fail("一个用户不能重复下单");
        try{
            //这里调用的是对象本身的方法会导致事务失效,也就是说当类内的方法调用另一个被注解注释的方法时会失效
            IVoucherOrderService iVoucherOrderService = (IVoucherOrderService)AopContext.currentProxy();
            return iVoucherOrderService.createOrder(voucherId);
        }
        finally{
            lock.unlock();
        }*/
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisWorker.nextId("order");
        Long status = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString(),String.valueOf(orderId)
        );
        int r = status.intValue();

        if(r != 0){
            //表示库存不足或者用户已经下单
            return Result.fail(r == 1 ? "用户已经下单" : "库存不足");
        }

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("order:" + userId);

        //使用了redisson提供的锁，可重入
        boolean success = lock.tryLock();
        if (success) {
            try {
                proxy.createOrder(voucherOrder);
            } catch (Exception e) {
                log.error("{}", e);
            } finally {
                lock.unlock();
            }
        }
    }

    @Transactional
    @Override
    public void createOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //注意：这里也需要进行查询因为有可能用户下单之后没有进行付款
        Long count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if(count > 0){
            log.error("不能重复下单");
            return ;
        }

//      乐观锁的概念:多个线程可能都会获取到相同的库存，但是有其中一个修改之后其他线程就终止。
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0).update();
        if(!success){
            log.error("不能重复下单");
            return ;
        }
        save(voucherOrder);
    }
//    @Transactional
//    public Result createOrder(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //这里可能会出现线程安全多个线程都获得了相同的数量，导致多次购买
//        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if(count > 0) return Result.fail("一个用户只能购买一次");
//
//        //乐观锁的概念:多个线程可能都会获取到相同的库存，但是有其中一个修改之后其他线程就终止。
//        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId).gt("stock",0).update();
//        if(!success) return Result.fail("库存更新失败");
//
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //生成订单id
//        long id = redisWorker.nextId("voucher");
//        voucherOrder.setId(id);
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(userId);
//
//        save(voucherOrder);
//        return Result.ok(id);
//    }
}

package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisWorker redisWorker;

    //resource和autowired的区别：
    //resource注入首先会根据注入的名称查找对应的bean，之后再根据类名来找
    //autowired则会先去优先找对应的类名

    //更新数据库时可能会出现，两个线程查询到的数据是一样的，但是有一个快一个慢，导致他们都会对库存进行减一
    //也就是线程安全问题：多个线程对同一数据进行操作，可能会导致数据错误。
    //可以加入乐观锁(有cas法和版本号法)

    @Transactional //保证数据库的一致性所以使用事务注解，数据库数据回滚
    @Override
    public Result seckillVoucher(Long voucherId) {
        //根据id查询秒杀优惠券
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
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        if(!simpleRedisLock.tryLock(1200)) return Result.fail("一个用户不能重复下单");
        try{
            //这里调用的是对象本身的方法会导致事务失效
            IVoucherOrderService iVoucherOrderService = (IVoucherOrderService)AopContext.currentProxy();
            return iVoucherOrderService.createOrder(voucherId);
        }
        finally{
            simpleRedisLock.unlock();
        }
    }

    @Transactional
    public Result createOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //这里可能会出现线程安全多个线程都获得了相同的数量，导致多次购买
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0) return Result.fail("一个用户只能购买一次");

        //乐观锁的概念:多个线程可能都会获取到相同的库存，但是有其中一个修改之后其他线程就终止。
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0).update();
        if(!success) return Result.fail("库存更新失败");

        VoucherOrder voucherOrder = new VoucherOrder();
        //生成订单id
        long id = redisWorker.nextId("voucher");
        voucherOrder.setId(id);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);

        save(voucherOrder);
        return Result.ok(id);
    }
}

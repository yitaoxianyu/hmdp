package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
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

    @Resource
    private RedisWorker redisWorker;

    //resource和autowired的区别：
    //resource注入首先会根据注入的名称查找对应的bean，之后再根据类名来找
    //autowired则会先去优先找对应的类名
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

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

        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id",voucherId).update();
        if(!success) return Result.fail("库存更新失败");

        VoucherOrder voucherOrder = new VoucherOrder();
        //生成订单id
        long id = redisWorker.nextId("voucher");
        voucherOrder.setId(id);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());

        voucherOrderService.save(voucherOrder);
        return Result.ok(id);
    }
}

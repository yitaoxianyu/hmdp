package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //缓存穿透：是指用户查询的数据不在缓存和数据库中，进行多次查询会导致数据库压力过大
    //解决方法：可以使用存储空对象的方法来防止，当查询不存在的数据时，可以缓存一个空对象
    //缓存击穿：指的是一定时间内多次访问一个热点数据，导致大量查询打到数据库上
    //解决方法：加一个互斥锁（悲观锁）用来控制查询
    @Override
    public Result queryById(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //在hutool工具类中，空字符串和null都会被判断为空
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        if(shopJson != null){
            //为空字符串
            return Result.fail("店铺不存在！");
        }
        //这里需要争抢互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = getLock(lockKey);
            if(!isLock){
                Thread.sleep(50);
                queryById(id);
            }
            shop = getById(id);
            Thread.sleep(200);
            if(shop == null){
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",
                        RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            String jsonStr = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,jsonStr,
                    RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return Result.ok(shop);
    }

    private boolean getLock(String key){
        //为封装类要进行拆箱
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) return Result.fail("id不为空");

        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}

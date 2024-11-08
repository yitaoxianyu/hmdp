package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogical(String key,Object value,Long time,TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public<T,R> R queryWithPassThrough(String keyPrefix, T id,
                                       Class<R> type, Function<T,R> dbFallBack,Long time,TimeUnit unit){

        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);

        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        if(json != null){
            return null;
        }
        R r = dbFallBack.apply(id);
        if(r == null){
            stringRedisTemplate.opsForValue().set(keyPrefix + id,"",time,unit);
            return null;
        }
        stringRedisTemplate.opsForValue().set(keyPrefix + id,JSONUtil.toJsonStr(r),time,unit);
        return r;
    }

    private boolean getLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    //这里开放10个线程的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <T,R> R queryWithLogicalExpire(String keyPrefix,T id,Class<R> type,
                                          Function<T,R> dbFallBack,Long time,TimeUnit unit){
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if(StrUtil.isBlank(json)) return null;

        RedisData redisData = BeanUtil.toBean(json,RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())) return r;
        //如果过期会争抢锁
        boolean flag = getLock(RedisConstants.LOCK_SHOP_KEY + id);
        if(flag){
            //获取新线程来进行数据更新
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try{
                    R r1 = dbFallBack.apply(id);
                    setWithLogical(keyPrefix + id,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        return r; //没有争抢到锁，返回一个旧的数据
    }
}

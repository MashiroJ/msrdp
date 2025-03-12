package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


/**
 * @ClassName CacheClient
 * @Description 缓存工具类
 * @Author 猫爪在上
 * @Date 2024/12/12 23:40
 * @Version 1.0
 */
@Component
public class CacheClient {

    //存入Reids中的空值的过期时间
    public static final Long CACHE_NULL_TTL = 2L;
    //存入Reids中的空值的过期时间的时间类型
    public static final TimeUnit CACHE_NULL_TIME_UNIT = TimeUnit.MINUTES;
    //互斥锁对应的key
    public static final String LOCK_KEY = "lock:";
    //获取互斥锁失败后的等待时间（单位毫秒）
    public static final Long SPIN_WAIT_MILLISECOND = 50L;

    //使用构造函数注入StringRedisTemplate
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //创建拥有十个线程的线程池，用来重建缓存，避免经常创建销毁线程
    private final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     *
     * @param key      String类型的Key
     * @param value    任意类型的对象
     * @param time     过期时间
     * @param timeUnit 时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     *
     * @param key      String类型的Key
     * @param value    任意类型的对象
     * @param time     逻辑过期时间
     * @param timeUnit 时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *
     * @param keyPrefix  key的前缀
     * @param id         id
     * @param type       需要返回的对象的Class类型
     * @param dbFallback 根据id进行数据库查询的函数
     * @param time       过期时间
     * @param timeUnit   时间单位
     * @param <R>        需要返回的对象类型的泛型
     * @param <ID>       id的泛型
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix,
                                          ID id,
                                          Class<R> type,
                                          Function<ID, R> dbFallback,
                                          Long time,
                                          TimeUnit timeUnit) {
        //1、从redis中根据id查询商铺
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在记录
        if (StrUtil.isNotBlank(json)) {
            //存在，返回数据
            R r = JSONUtil.toBean(json, type);
            return r;
        }

        //3、判断记录是否为空值
        if (json != null) {
            return null;
        }

        //4、查询数据库
        R r = dbFallback.apply(id);
        //5、数据库是否存在记录
        if (r == null) {
            //6、不存在，将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, CACHE_NULL_TIME_UNIT);
            return null;
        }
        //7、存在，保存数据到redis，返回数据
        this.set(key, r, time, timeUnit);
        return r;
    }


    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
     *
     * @param keyPrefix  key的前缀
     * @param id         id
     * @param type       需要返回对象的Class类型
     * @param dbFallback 根据id查询数据库
     * @param time       逻辑过期时间
     * @param timeUnit   时间单位
     * @param <R>        需要返回的对象类型的泛型
     * @param <ID>       id的泛型
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix,
                                            ID id,
                                            Class<R> type,
                                            Function<ID, R> dbFallback,
                                            Long time,
                                            TimeUnit timeUnit) {
        //1、从redis中根据id查询商铺
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、缓存未命中，返回空数据
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //3、缓存命中
        RedisData cacheData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = cacheData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) cacheData.getData(), type);

        //3.1、判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //3.2、缓存未过期，直接返回数据
            return r;
        }

        //4、缓存过期，需要进行缓存重建
        //4.1、尝试获取互斥锁
        String lockKey = LOCK_KEY + id;
        boolean isLock = tryLock(lockKey);
        //4.2、互斥锁获取成功
        if (isLock) {
            //4.3、再次检测redis缓存是否过期，做DoubleCheck
            String doubleCheckCacheStr = stringRedisTemplate.opsForValue().get(key);
            RedisData redisData = JSONUtil.toBean(doubleCheckCacheStr, RedisData.class);
            LocalDateTime newExpireTime = redisData.getExpireTime();
            R newR = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            //4.3、缓存未过期（已经有线程重建完成了），则返回数据
            if (newExpireTime.isAfter(LocalDateTime.now())) {
                return newR;
            }
            //4.4 缓存仍过期 （还没有其他的线程重建缓存），创建独立线程，重建缓存
            //将重建工作交给线程池完成
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R dbR = dbFallback.apply(id);
                    //重建缓存
                    this.setWithLogicalExpire(key, dbR, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //4.5释放锁
                    unlock(lockKey);
                }
            });
        }
        //5、返回过期的商铺信息
        return r;
    }

    /**
     * 根据指定的key查询缓存，并反序列化为指定类型，利用互斥锁解决缓存击穿问题
     *
     * @param keyPrefix  key的前缀
     * @param id         id
     * @param type       需要返回对象的Class类型
     * @param dbFallback 根据id查询数据库
     * @param time       过期时间
     * @param timeUnit   时间单位
     * @param <R>        需要返回的对象类型的泛型
     * @param <ID>       id的泛型
     * @return
     */
    public <R, ID> R queryWithMutex(String keyPrefix,
                                    ID id,
                                    Class<R> type,
                                    Function<ID, R> dbFallback,
                                    Long time,
                                    TimeUnit timeUnit) {
        //1、从redis中根据id查询商铺
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在记录
        if (StrUtil.isNotBlank(json)) {
            //存在，返回数据
            R r = JSONUtil.toBean(json, type);
            return r;
        }

        //3、判断记录是否为空值
        if (json != null) {
            return null;
        }

        //4、redis 查询结果为null缓存失效，尝试重建缓存
        String lockKey = LOCK_KEY + id;
        R dbR = null;
        try {
            //自旋等待，尝试获取互斥锁
            while (!tryLock(lockKey)) {
                Thread.sleep(SPIN_WAIT_MILLISECOND);
            }

            //4.2、获取锁成功,再次查询缓存
            String newJson = stringRedisTemplate.opsForValue().get(key);
            //缓存有效，直接返回
            if (StrUtil.isNotBlank(newJson)) {
                //存在，返回数据
                return JSONUtil.toBean(newJson, type);
            }

            //4.3、缓存无效，查询数据库重建缓存
            dbR = dbFallback.apply(id);
            //数据库是否存在记录
            if (dbR == null) {
                //不存在，将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, CACHE_NULL_TIME_UNIT);
                return null;
            }
            //存在，保存数据到redis，返回数据
            this.set(key, dbR, time, timeUnit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //5、释放锁
            unlock(lockKey);
        }
        //返回数据
        return dbR;
    }

    /**
     * 获取互斥锁，利用 setnx设置互斥锁，并设置锁的过期时间
     *
     * @param key
     * @return
     */
    public boolean tryLock(String key) {
        Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(isLock);
    }

    /**
     * 释放互斥锁
     *
     * @param key
     */
    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
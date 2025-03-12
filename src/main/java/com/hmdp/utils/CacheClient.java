package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 设置Redis键值对并指定物理过期时间
     *
     * @param key   Redis键
     * @param value 存储的值对象
     * @param time  过期时间长度
     * @param unit  时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        // 将对象转换为JSON字符串，并存入Redis，同时设置过期时间，到期后Redis会自动删除该键
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    /**
     * 缓存穿透防护查询
     * 通过缓存空值来防止对不存在数据的反复查询导致数据库压力过大
     *
     * @param keyPrefix  Redis键前缀
     * @param id         数据id
     * @param type       返回数据类型的Class
     * @param dbFallback 查询数据库的函数式接口，当缓存未命中时执行
     * @param time       缓存存活时间
     * @param unit       时间单位
     * @param <R>        返回数据类型
     * @param <ID>       ID类型
     * @return 查询结果，可能为null
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 构建Redis键
        String key = keyPrefix + id;

        // 1.从Redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在有效数据（非空字符串）
        if (StrUtil.isNotBlank(json)) {
            // 3.存在有效数据，将JSON转为对象并返回
            return JSONUtil.toBean(json, type);
        }

        // 判断命中的是否是空值（空字符串，表示数据库中确实没有此数据）
        if (json != null) {
            // 返回null表示数据不存在
            return null;
        }

        // 4.缓存未命中，根据id查询数据库
        R r = dbFallback.apply(id);

        // 5.数据库中也不存在该数据
        if (r == null) {
            // 将空值写入Redis，防止缓存穿透，CACHE_NULL_TTL是空值的过期时间（一般较短）
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回null表示数据不存在
            return null;
        }

        // 6.数据库中存在该数据，写入Redis缓存并设置过期时间
        this.set(key, r, time, unit);

        // 返回数据库查询结果
        return r;
    }

    /**
     * 缓存击穿方案，设置Redis逻辑过期机制
     *
     * @param key   Redis键
     * @param value 存储的值对象
     * @param time  过期时间长度
     * @param unit  时间单位
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 创建RedisData对象，用于封装数据和逻辑过期时间
        RedisData redisData = new RedisData();

        // 设置实际数据
        redisData.setData(value);

        // 计算并设置逻辑过期时间点
        // 当前时间加上传入的时间值，单位统一转换为秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 将RedisData对象转为JSON字符串并写入Redis
        // 注意：此处没有设置Redis的TTL，数据不会自动过期
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 使用互斥锁解决缓存击穿问题的查询方法
     *
     * @param keyPrefix  Redis键前缀
     * @param id         数据id
     * @param type       返回数据类型的Class
     * @param dbFallback 查询数据库的函数式接口
     * @param time       缓存有效时间
     * @param unit       时间单位
     * @param <R>        返回数据类型
     * @param <ID>       ID类型
     * @return 查询结果，可能为null
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 构建Redis键
        String key = keyPrefix + id;

        // 1.从Redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在有效数据
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在有效数据，将JSON转为对象并返回
            return JSONUtil.toBean(shopJson, type);
        }

        // 判断命中的是否是空值（防止缓存穿透的空字符串标记）
        if (shopJson != null) {
            // 返回null表示数据不存在
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁（分布式锁）
        String lockKey = LOCK_KEY + id;
        R r = null;
        try {
            // 尝试获取锁
            boolean isLock = tryLock(lockKey);

            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，说明其他线程正在重建缓存，当前线程休眠一段时间后重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }

            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);

            // 5.数据库中不存在该数据
            if (r == null) {
                // 将空值写入Redis防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回null表示数据不存在
                return null;
            }

            // 6.数据库中存在该数据，写入Redis缓存
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.无论成功失败，都要释放互斥锁
            unlock(lockKey);
        }

        // 8.返回数据库查询结果
        return r;
    }

    /**
     * 尝试获取分布式锁
     *
     * @param key 锁的键
     * @return 是否获取成功
     */
    private boolean tryLock(String key) {
        // 使用Redis的SETNX命令实现分布式锁，同时设置过期时间防止死锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 避免自动拆箱时出现NPE
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放分布式锁
     *
     * @param key 锁的键
     */
    private void unlock(String key) {
        // 删除锁对应的key即可释放锁
        stringRedisTemplate.delete(key);
    }


}

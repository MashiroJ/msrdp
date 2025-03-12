package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryShopById(Long id) {
        //通过缓存空值，缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,
//                id, Shop.class,  this::getById, CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
//        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY,id,Shop.class,
//                this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, 10L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 缓存击透通过ID查询店铺
     * 缓存击穿是指热点key在过期的一瞬间，同时有大量的请求打到数据库上，导致数据库压力骤增。
     * 解决方案通常是使用互斥锁，保证同一时间只有一个线程去查询数据库，其他线程等待。
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        //1、查询缓存：首先根据商铺ID从Redis缓存中查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String cachedShop = stringRedisTemplate.opsForValue().get(key);
        //2、缓存命中：如果Redis中存在该商铺数据，则直接返回反序列化后的对象
        if (StrUtil.isNotBlank(cachedShop)) {
            //
            return JSONUtil.toBean(cachedShop, Shop.class);
        }

        //判断命中的是否是空值（防止缓存穿透）
        if ("".equals(cachedShop)) {
            // 返回一个空值
            return null;
        }

        //3、缓存未命中，实现缓存重建
        //3.1 获取缓存互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            //自旋等待，尝试获取互斥锁
            boolean isLock = tryLock(lockKey);
            // 获取锁失败，休眠一段时间后重试
            if (!isLock) {
                Thread.sleep(50);
                // 递归调用，重新走一遍流程
                return queryWithMutex(id);
            }

            //3.2、获取锁成功，进行Redis double check
            String dcShop = stringRedisTemplate.opsForValue().get(key);
            //缓存有效，直接返回
            if (StrUtil.isNotBlank(dcShop)) {
                //存在，返回数据
                return JSONUtil.toBean(dcShop, Shop.class);
            }
            // 判断是否为空字符串（防止缓存穿透）
            if ("".equals(dcShop)) {
                return null;
            }

            //3.3、缓存仍然无效，查询数据库重建缓存
            //模拟缓存重建延时
            Thread.sleep(2000);

            shop = getById(id);
            //数据库是否存在记录
            if (shop == null) {
                //不存在，将空值写入redis，设置短暂的过期时间防止缓存穿透
                stringRedisTemplate.opsForValue()
                        .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //存在，保存数据到redis并设置过期时间（使用一个原子操作），并添加随机过期时间，避免缓存雪崩
            long expireTime = CACHE_SHOP_TTL + RandomUtil.randomLong(0, CACHE_SHOP_TTL / 5);
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), expireTime, TimeUnit.MINUTES);
        } catch (Exception e) {
            // 捕获所有异常，确保锁能够释放
            log.error("查询商铺数据异常", e);
            throw new RuntimeException("查询商铺数据异常", e);
        } finally {
            //4、释放锁
            unlock(lockKey);
        }
        //返回数据
        return shop;
    }

    /**
     * 逻辑过期解决缓存击穿
     * 缓存击穿是指热点key在过期的一瞬间，同时有大量的请求打到数据库上，导致数据库压力骤增。
     * 解决方案是给缓存设置一个逻辑过期时间，当访问到数据时，如果发现数据即将过期，则启动一个后台线程去更新缓存。
     *
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id) {
        // 1. 从Redis查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 不存在，直接返回null
            return null;
        }

        // 3. 存在，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 4.1 未过期，直接返回店铺信息
            return shop;
        }

        // 4.2 已过期，需要缓存重建
        // 5. 缓存重建
        // 5.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 5.2 判断是否获取锁成功
        if (isLock) {
            // 5.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    log.error("缓存重建异常", e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 6. 返回过期的商铺信息
        return shop;
    }

    /**
     * 缓存穿透通过ID查询店铺
     * 缓存穿透是指查询一个不存在的key，由于缓存中不存在该key，因此每次查询都会直接打到数据库上，导致数据库压力骤增。
     * 当我们发现请求的数据即不存在于缓存，也不存于与数据库时，将空值缓存到Redis，并设置过期时间，避免频繁查询数据库。
     *
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        //1、查询缓存：首先根据商铺ID从Redis缓存中查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String cachedShop = stringRedisTemplate.opsForValue().get(key);
        //2、缓存命中：如果Redis中存在该商铺数据，则直接返回反序列化后的对象
        if (StrUtil.isNotBlank(cachedShop)) {
            return JSONUtil.toBean(cachedShop, Shop.class);
        }

        //3、空值判断：如果Redis返回null，说明该key不存在，直接返回null
        if (cachedShop == null) {
            return null;
        }

        //4、查询数据库：如果缓存未命中，则从数据库查询
        Shop shop = getById(id);
        //5、数据库不存在记录：如果数据库中也没有该商铺，将 空字符串 写入Redis并设置短期过期时间
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //7、数据库存在记录：如果数据库中有该商铺，则将其序列化后写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        //8、设置店铺数据过期时间 CACHE_SHOP_TTL = 30L
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 更新商铺信息时，
     * 先操作数据库再删除缓存，
     * 同时将这两个操作放在一个事务中执行。
     */
    @Transactional
    public Result updateShop(Shop shop) {
        //1、验证数据有效性
        if (shop.getId() == null)
            return Result.fail("店铺ID不能为空");
        //2、更新数据库
        updateById(shop);
        //3、删除店铺缓存
        String key = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(key);
        return Result.ok();
    }


    /**
     * 将商铺数据存入Redis，并设置逻辑过期时间
     */
    public void saveShopToRedis(Long id, Long expireSeconds) {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis (注意：这里不设置TTL)
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 获取互斥锁，利用 setnx设置互斥锁，并设置锁的过期时间
     *
     * @param key
     * @return
     */
    public boolean tryLock(String key) {
        Boolean isLock = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
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

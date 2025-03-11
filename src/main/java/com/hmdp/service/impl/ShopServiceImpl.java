package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {
        //缓存穿透
        Shop shop = queryWithPassThrough(id);
        return Result.ok(shop);
    }

    //更新商铺信息时，先操作数据库再删除缓存，同时将这两个操作放在一个事务中执行。
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
     * 缓存穿透通过ID查询店铺
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

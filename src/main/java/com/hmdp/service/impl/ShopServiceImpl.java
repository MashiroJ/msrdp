package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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

    @Resource StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {
        //1、从redis中根据id查询商铺
        String key = CACHE_SHOP_KEY + id;
        String cachedShop = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在记录
        if (StrUtil.isNotBlank(cachedShop)) {
            //3、存在，返回数据
            Shop shop = JSONUtil.toBean(cachedShop, Shop.class);
            return Result.ok(shop);
        }
        //4、不存在，查询数据库
        Shop shop = getById(id);
        //5、数据库是存在记录
        if (shop == null) {
            //6、不存在，返回错误信息
            return Result.fail("店铺不存在");
        }
        //7、存在，保存数据到redis，返回数据
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        return Result.ok(shop);
    }
}

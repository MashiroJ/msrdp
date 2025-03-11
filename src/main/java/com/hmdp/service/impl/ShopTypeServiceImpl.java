package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        //1、从Redis缓存中获取商店类型列表的所有元素（从头到尾）
        List<String> shopType = stringRedisTemplate
                .opsForList()
                .range(SHOP_TYPE, 0, -1);
        //2、是否存在记录
        if (CollectionUtil.isNotEmpty(shopType)) {
            //3、存在，返回数据
            return shopType
                    .stream()
                    .map(str -> JSONUtil.toBean(str, ShopType.class))
                    .collect(Collectors.toList());
        }
        //4、不存在，从数据库中查询
        List<ShopType> typeList = query()
                .orderByAsc("sort")
                .list();
        //5、添加结果到Redis，返回数据     List中的是String类型，进行类型转化
        List<String> shopTypeStr = typeList.stream()
                .map(shopType1 -> JSONUtil.toJsonStr(shopType1))
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(SHOP_TYPE, shopTypeStr);
        return typeList;
    }
}

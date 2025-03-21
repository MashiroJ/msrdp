package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IFollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1、获得当前用户id
        Long id = UserHolder.getUser().getId();
        //2、判断是关注还是取关
        if (isFollow) {
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                String key = "follows:" + id;
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            //取关，删除数据 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, id)
                    .eq(Follow::getFollowUserId, followUserId));
            if (isSuccess) {
                String key = "follows:" + id;
                stringRedisTemplate.opsForSet().remove(key, followUserId);
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1、获得当前用户id
        Long id = UserHolder.getUser().getId();
        //2、查询数据库 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        int count = count(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, id)
                .eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //1、获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //2、求交集
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        Set<String> commonUserSet = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (commonUserSet == null || commonUserSet.isEmpty()) {
            // 无交集,五共同好友
            return Result.ok(Collections.emptyList());
        }
        //4、解析id集合
        List<Long> idList = commonUserSet.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        //3、查询相关用户，返回结果
        List<UserDTO> users = userService.listByIds(idList).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}

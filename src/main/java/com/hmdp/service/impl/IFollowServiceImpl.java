package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

@Service
public class IFollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Override
    public Result follow(long followUserId, Boolean isFollow) {
        //1、获得当前用户id
        Long id = UserHolder.getUser().getId();
        //2、判断是关注还是取关
        if (isFollow) {
            //关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            save(follow);
        } else {
            //取关，删除数据 delete from tb_follow where user_id = ? and follow_user_id = ?
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<Follow>().eq(Follow::getUserId, id).eq(Follow::getFollowUserId, followUserId);
            remove(queryWrapper);
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(long followUserId) {
        //1、获得当前用户id
        Long id = UserHolder.getUser().getId();
        //2、查询数据库 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", id).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }
}

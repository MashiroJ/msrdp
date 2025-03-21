package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

public interface IFollowService extends IService<Follow> {
    Result follow(long followUserId, Boolean isFollow);

    Result isFollow(long followUserId);
}

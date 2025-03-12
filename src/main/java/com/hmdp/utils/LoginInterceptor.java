package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * 登录验证拦截器：确保用户已登录才能访问受保护的资源
 * 该拦截器与RefreshTokenInterceptor配合使用：
 * 1. RefreshTokenInterceptor(order=0)先执行，负责恢复用户上下文和刷新token
 * 2. 本拦截器(order=1)后执行，专注于登录状态验证
 */
@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 从ThreadLocal中获取用户信息（由RefreshTokenInterceptor设置）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //2. 如果用户信息不存在，说明未登录，设置401状态码并拦截请求
            response.setStatus(401);
            return false;
        }
        //3. 如果用户信息存在，表示已登录，放行请求
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //清理ThreadLocal中的用户数据，避免内存泄漏
        UserHolder.removeUser();
    }
}

package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        //1. 获取请求头中的token令牌
        String token = request.getHeader("authorization");
//        log.info("请求路径：{}，token:{}", request.getRequestURL(), token);
        if (StrUtil.isBlank(token)) {
            //2.根据token从Redis查询用户数据，不存在则放行到登录拦截器
            return true;
        }

        //3. 将Redis存储的Hash数据转换为UserDTO对象
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate
                .opsForHash()
                .entries(tokenKey);

        if (userMap.isEmpty()) {
            //用户数据不存在，放行到登录拦截器
            return true;
        }

        //3.将查询到的Hash数据转化为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //4. 使用ThreadLocal工具类保存用户信息，实现线程隔离的用户上下文
        UserHolder.saveUser(userDTO);

        //5.刷新Redis中token的有效期，实现自动延长登录状态
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //6.无条件放行请求，让后续拦截器或处理器继续处理
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) throws Exception {
        //清理ThreadLocal中的用户数据，防止内存泄漏和数据混乱
        UserHolder.removeUser();
    }
}
package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
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
        //1.从请求头中获取token
        String token = request.getHeader("authorization");
        log.info("请求路径：{}，token:{}",request.getRequestURL(),token);
        if (StrUtil.isBlank(token)) {
            //token不存在，放行到登录拦截器
            return true;
        }

        //2.从Redis获取用户数据
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

        //4.存在将用户信息保存到ThreadLocal中
        //此处使用自定义的ThreadLocal工具类完成(创建ThreadLocal对象，添加UserDTO)
        UserHolder.saveUser(userDTO);

        //5.刷新token时间
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //6.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
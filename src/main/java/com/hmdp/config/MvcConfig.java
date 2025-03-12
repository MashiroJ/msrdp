package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private LoginInterceptor loginInterceptor;
    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        /**
         * 配置登录拦截器：拦截所有请求路径("/**")，检查用户是否已登录，
         * 同时排除无需登录即可访问的路径，如商铺信息、优惠券、上传、博客热点、
         * 用户验证码和登录接口等公开资源。order(1)设置拦截器优先级较高，
         * 确保用户访问受保护资源前先进行身份验证。
         */
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);

        /**
         * 配置Token刷新拦截器：拦截所有请求("/**")，优先级设为0(order(0))，
         * 确保在登录拦截器之前执行。主要用于检查用户令牌有效性，
         * 自动刷新即将过期的令牌，并将用户信息存入上下文，
         * 为后续的登录拦截器和业务逻辑提供基础支持。
         */
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .order(0); //先执行
    }
}

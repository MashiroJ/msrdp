package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

@Configuration
public class RedissonConfig {
    @Resource
    private RedisProperties redisProperties;

    @Bean
    public RedissonClient redissonClient() {
        //配置
        Config config = new Config();   //引入 Redisson 的Config类型
        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
        String password = redisProperties.getPassword();
        //创建单机模式配置,如果是集群则使用 config.useClusterServers()
        config.useSingleServer().setAddress(address).setPassword(password);
        //创建RedissonClient对象
        return Redisson.create(config);
    }
}
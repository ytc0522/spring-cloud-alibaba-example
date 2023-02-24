package com.example.user.controller;

import com.example.common.entities.UserEntity;
import com.example.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RefreshScope // 该注解可以动态更新nacos上的配置
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Value("${server.port}")
    private String port;

    @Value("${system.user.city}")
    private String city;

    @Resource
    private UserService userService;

    @Resource
    private ConfigurableApplicationContext applicationContext;

    @GetMapping("/{id}")
    public UserEntity getById(@PathVariable("id") String id,boolean isTimeout){
        log.info("Enter App port:{}" , port);
        log.info("Current city:{}",city);

        /**
         * 演示如果服务调用超时了，会怎么样？
         * 结果：调用方在调用时，发现超时会对其他的节点进行重试，如果都超时，则报错
         * ：java.net.SocketTimeoutException: Read timed out
         */
        if (isTimeout){
            try {
                Thread.sleep(10 * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            log.info("Exit sleep");
        }
        UserEntity userEntity = userService.getById(id);
        userEntity.setCity(city);
        return userEntity;
    }
}

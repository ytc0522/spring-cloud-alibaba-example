package com.example.user.controller;

import com.example.common.entities.UserEntity;
import com.example.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Value("${server.port}")
    private String port;

    @Resource
    private UserService userService;

    @GetMapping("/{id}")
    public UserEntity getById(@PathVariable("id") String id){
        log.info("Enter App port:{}" , port);
        return userService.getById(id);
    }





}

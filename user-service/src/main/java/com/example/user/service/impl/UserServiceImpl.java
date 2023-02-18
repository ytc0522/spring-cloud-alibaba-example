package com.example.service.impl;

import com.example.common.entities.UserEntity;
import com.example.service.UserService;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

public class UserServiceImpl implements UserService {

    private Map<String,UserEntity> userMap = new HashMap<>();

    @PostConstruct
    public void init(){
        UserEntity userEntity = new UserEntity();
        userEntity.setUserId("1001");
        userEntity.setUserName("王八犊子");
        userEntity.setEmail("6766666@gmail.com");
        userMap.put(userEntity.getUserId(),userEntity);
    }

    @Override
    public UserEntity getById(String userId) {
        return userMap.get(userId);
    }
}

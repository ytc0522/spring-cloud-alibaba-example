package com.example.order.service;

import com.example.common.entities.UserEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(value = "user-service")
public interface UserService {

    @GetMapping("/user/{id}")
    UserEntity getById(@PathVariable("id") String id);
}

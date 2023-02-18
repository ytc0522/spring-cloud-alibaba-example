package com.example.service;

import com.example.common.entities.UserEntity;
import org.springframework.stereotype.Service;

@Service
public interface UserService {

    UserEntity getById(String userId);

}

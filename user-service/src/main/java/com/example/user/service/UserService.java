package com.example.user.service;

import com.example.common.entities.UserEntity;
import org.springframework.stereotype.Service;

public interface UserService {

    UserEntity getById(String userId);

}

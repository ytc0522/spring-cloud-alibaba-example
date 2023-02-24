package com.example.order.service;


import com.example.common.entities.OrderEntity;
import com.example.common.entities.UserOrderEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

public interface OrderService {

    public OrderEntity getById(String orderId);




}

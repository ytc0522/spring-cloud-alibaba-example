package com.example.order.service.impl;

import com.example.common.entities.OrderEntity;
import com.example.order.service.OrderService;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class OrderServiceImpl implements OrderService {

    private Map<String,OrderEntity> orderMap= new HashMap<>();

    @PostConstruct
    public void init(){
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderId("1001");
        orderEntity.setUserId("1001");
        orderEntity.setCreateTime(new Date());
        orderMap.put(orderEntity.getOrderId(),orderEntity);
    }

    @Override
    public OrderEntity getById(String orderId) {
        return orderMap.get(orderId);
    }
}

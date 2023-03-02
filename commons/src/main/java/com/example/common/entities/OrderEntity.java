package com.example.common.entities;

import lombok.Data;

import java.util.Date;

@Data
public class OrderEntity {

    private String orderId;

    private String userId;

    private Date createTime;

    private Integer status;


}

package com.example.common.entities;

import lombok.Data;

import java.util.Date;

@Data
public class UserOrderEntity {

    private String orderId;

    private String userId;

    private Date createTime;

}

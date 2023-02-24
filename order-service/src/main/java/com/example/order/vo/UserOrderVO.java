package com.example.order.vo;

import lombok.Data;

import java.util.Date;

@Data
public class UserOrderVO {

    private String orderId;

    private String userId;

    private String userName;

    private String userEmail;

    private Date createTime;

}

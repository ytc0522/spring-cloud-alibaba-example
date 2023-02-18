package com.example.order.controller;

import com.example.common.entities.OrderEntity;
import com.example.common.entities.UserEntity;
import com.example.order.service.OrderService;
import com.example.order.service.UserService;
import com.example.order.vo.UserOrderVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.management.remote.rmi._RMIConnection_Stub;
import java.util.List;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    /**
     * 分别查询后聚合在一起
     * @param orderId
     * @return
     */
    @GetMapping("/{id}")
    public UserOrderVO getById(@PathVariable("id") String orderId){
        OrderEntity order = orderService.getById(orderId);
        if (order == null) {
            return null;
        }
        String userId = order.getUserId();
        UserEntity userEntity = userService.getById(order.getUserId());

        UserOrderVO userOrderVO = new UserOrderVO();
        userOrderVO.setOrderId(orderId);
        userOrderVO.setUserName(userEntity.getUserName());
        userOrderVO.setUserId(userId);
        userOrderVO.setUserEmail(userEntity.getEmail());
        userOrderVO.setCreateTime(order.getCreateTime());

        return userOrderVO;
    }


}

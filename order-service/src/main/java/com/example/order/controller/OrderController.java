package com.example.order.controller;

import com.example.common.entities.OrderEntity;
import com.example.common.entities.UserEntity;
import com.example.order.service.OrderService;
import com.example.order.service.UserService;
import com.example.order.vo.UserOrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.management.remote.rmi._RMIConnection_Stub;
import java.util.List;

@Slf4j
@RefreshScope
@RestController
@RequestMapping("/order")
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    @Value("${order.count}")
    private String orderCount;

    @Resource
    private ConfigurableApplicationContext applicationContext;

    @GetMapping("/count")
    public String count() {
        log.info("orderCount:{}", orderCount);
        return String.valueOf(orderCount);
    }

    /**
     * 分别查询后聚合在一起
     *
     * @param orderId
     * @return
     */
    @GetMapping("/{id}")
    public UserOrderVO getById(@PathVariable("id") String orderId) {
        OrderEntity order = orderService.getById(orderId);
        if (order == null) {
            return null;
        }
        String userId = order.getUserId();
        UserEntity userEntity = userService.getById(order.getUserId());

        UserOrderVO userOrderVO = new UserOrderVO();
        userOrderVO.setOrderId(orderId);
        userOrderVO.setCreateTime(order.getCreateTime());
        userOrderVO.setUserName(userEntity.getUserName());
        userOrderVO.setUserId(userId);
        userOrderVO.setUserEmail(userEntity.getEmail());

        return userOrderVO;
    }

    /**
     * 模拟下单
     * @param orderId
     * @return
     */
    @GetMapping("/mockOrder/{orderId}")
    public String mockOrder(@PathVariable("orderId") String orderId){
        orderService.mockOrder(orderId);
        return "SUCCESS";
    }


}

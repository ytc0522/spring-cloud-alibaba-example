package com.example.user.mq.consumer;


import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.messaging.Sink;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderConsumer {

    @StreamListener(Sink.INPUT)
    public void onMessage(String orderString) {
        System.out.println("order = " + orderString);
    }
}

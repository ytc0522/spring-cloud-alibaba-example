package com.example.sentinel.controller;


import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.sun.istack.internal.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FlowLimitController {

    final String RESOURCE_NAME = "RESOURCE_NAME";


    @SentinelResource(value = RESOURCE_NAME,blockHandler = "exceptionHandler")
    @GetMapping("/limit")
    public String anno() {
        return "common flow limit ,passed";
    }

    public String exceptionHandler(@NotNull BlockException e){
        return "blockHandler was invoked";
    }

/*
    @GetMapping("/get")
    public String get() {

        try {
            Entry product = SphU.entry(resource);
        } catch (BlockException e) {
            e.printStackTrace();
            log.info("限流了");
            return "系统繁忙，请稍后";
        }
        return "Macbook air";
    }


 @PostConstruct
    public void initFlowRules() {
        List<FlowRule> list = new ArrayList<>();

        FlowRule flowRule = new FlowRule();
        flowRule.setResource(resource);
        flowRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        flowRule.setCount(2);

        list.add(flowRule);
        FlowRuleManager.loadRules(list);
    }*/



}

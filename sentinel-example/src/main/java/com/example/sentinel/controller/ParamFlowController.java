package com.example.sentinel.controller;


import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PostConstruct;
import java.util.Collections;

@RestController
public class ParamFlowController {

    final String RESOURCE_NAME = "PARAM_RESOURCE";

    /**
     * 热点参数限流
     * 构造不同的uid的值，并且以不同的频率来请求该方法，查看效果
     */
    @GetMapping("/freqParamFlow")
    public String freqParamFlow(@RequestParam("uid") Long uid, @RequestParam("ip") Long ip) {
        Entry entry = null;
        String retVal;
        try{
            // 只对参数uid进行限流，参数ip不进行限制
            entry = SphU.entry(RESOURCE_NAME, EntryType.IN,1,uid);
            retVal = "passed";
        }catch(BlockException e){
            retVal = "blocked";
        }finally {
            if(entry!=null){
                entry.exit();
            }
        }
        return retVal;
    }

    @PostConstruct
    public void init(){
        // 定义热点限流的规则，对第一个参数设置 qps 限流模式，阈值为5
        ParamFlowRule rule = new ParamFlowRule(RESOURCE_NAME)
                .setParamIdx(0)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(5);
        ParamFlowRuleManager.loadRules(Collections.singletonList(rule));
    }

}

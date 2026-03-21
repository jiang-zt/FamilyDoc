package com.itzixi.service.impl;

import com.itzixi.bean.ChatMetric;
import com.itzixi.mapper.ChatMetricMapper;
import com.itzixi.service.ChatMetricService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class ChatMetricServiceImpl implements ChatMetricService {

    @Resource
    private ChatMetricMapper chatMetricMapper;

    @Override
    public void saveMetric(ChatMetric metric) {
        chatMetricMapper.insert(metric);
    }
}

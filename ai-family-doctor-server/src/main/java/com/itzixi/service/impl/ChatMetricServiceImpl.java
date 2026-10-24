package com.itzixi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.itzixi.bean.ChatMetric;
import com.itzixi.mapper.ChatMetricMapper;
import com.itzixi.service.ChatMetricService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatMetricServiceImpl implements ChatMetricService {

    @Resource
    private ChatMetricMapper chatMetricMapper;

    @Override
    public void saveMetric(ChatMetric metric) {
        chatMetricMapper.insert(metric);
    }

    @Override
    public List<ChatMetric> listRecentMetrics(String userName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));//列出用户userName的最多100条聊天记录
        QueryWrapper<ChatMetric> queryWrapper = new QueryWrapper<>();
        if (userName != null && !userName.trim().isEmpty()) {
            queryWrapper.eq("user_name", userName.trim());
        }
        queryWrapper.orderByDesc("created_at");//降序
        queryWrapper.last("limit " + safeLimit);//取最新100条，在sql语句后追加limit 100
        return chatMetricMapper.selectList(queryWrapper);
    }
    //ORDER BY created_at DESC LIMIT 100
}

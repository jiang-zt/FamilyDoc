package com.itzixi.service;

import com.itzixi.bean.ChatMetric;

import java.util.List;

public interface ChatMetricService {
    void saveMetric(ChatMetric metric);

    List<ChatMetric> listRecentMetrics(String userName, int limit);
}

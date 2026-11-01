package com.itzixi.security;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** 有界、线程安全的单实例固定时间窗。返回 0 表示允许，否则为等待秒数。 */
public final class RequestQuota {
    Clock clock;
    private final int capacity;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private record Bucket(long until, int count) {}
    public RequestQuota(Clock clock, int capacity) { this.clock = clock; this.capacity = capacity; }
    public synchronized long take(String key, int limit, long seconds) {
        if (limit < 1 || seconds < 1) throw new IllegalArgumentException("限流参数必须为正数");
        long now = clock.millis();
        Bucket bucket = buckets.get(key);
        if (bucket != null && bucket.until > now) {
            if (bucket.count >= limit) return Math.max(1, (bucket.until - now + 999) / 1000);
            buckets.put(key, new Bucket(bucket.until, bucket.count + 1)); return 0;
        }
        if (buckets.size() >= capacity) {
            buckets.entrySet().removeIf(entry -> entry.getValue().until <= now);
            if (buckets.size() >= capacity && !buckets.containsKey(key)) return 60;
        }
        buckets.put(key, new Bucket(now + seconds * 1000, 1)); return 0;
    }
}

package com.itzixi.traffic;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix="chat.traffic-recording")
public class TrafficRecordingProperties {
    private boolean enabled = false;
    private String directory = "./logs/traffic";
    private String instanceId = "local";
    private String actorHashKey = "";//做哈希脱敏的密钥（salt/key）
    private double sampleRate = 1.0;//全量录制
    private int queueCapacity = 256;
    private long maxFileBytes = 10485760;
    private int maxRecordBytes = 524288;
    private int retentionDays = 7;
    private long shutdownTimeoutMs = 5000;//关给最多 5 秒把剩余记录 flush 完
    void validate() {
        if (actorHashKey == null || actorHashKey.length() < 16) throw new IllegalArgumentException("启用流量录制需设置至少 16 字符的 actor-hash-key");
        if (!Double.isFinite(sampleRate) || sampleRate < 0 || sampleRate > 1 || queueCapacity < 1
                || maxFileBytes < 2 || maxRecordBytes < 1 || retentionDays < 1 || shutdownTimeoutMs < 1) {
            throw new IllegalArgumentException("流量录制配置无效，请检查采样率、队列、文件大小、保留天数和关闭超时");
        }
    }
}

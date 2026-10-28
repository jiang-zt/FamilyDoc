package com.itzixi.traffic;
import java.util.List;
import java.util.Map;
//定义不可变样本，复制原始 message + history，避免后续修改影响录制
public record TrafficSample(int schemaVersion, String sampleId, String requestId, String recordedAt,
                            String actorKey, String instanceId, String method, String path, RequestBody requestBody) {
    public record RequestBody(String message, List<Map<String,String>> history) {
        public RequestBody { history = history.stream().map(m -> Map.of("role",m.get("role"),"content",m.get("content"))).toList(); }
    }
}

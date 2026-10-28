package com.itzixi.traffic;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzixi.bean.ChatEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
//HMAC 用户匿名化、采样、入队；独立线程消费，处理丢弃计数和关闭排空
/** 有界、尽力录制；请求线程只快照与 offer，所有文件 IO 和序列化在独立线程完成。 */
@Slf4j
@Service
public class TrafficRecorder implements AutoCloseable {
    public static final String ARRIVAL_ATTRIBUTE=TrafficRecorder.class.getName()+".arrival";
    private final TrafficRecordingProperties properties;
    private final ObjectMapper mapper;
    private final TrafficFileWriter writer;
    private final Object lifecycle=new Object();//保护 accepting 与 queue.offer 的"关闭竞态"
    //入队成功数、写盘成功数、丢弃数、采样排除数
    private final LongAdder accepted=new LongAdder(),written=new LongAdder(),dropped=new LongAdder(),sampledOut=new LongAdder();
    public enum DropReason { QUEUE_FULL, RECORD_TOO_LARGE, SNAPSHOT_FAILED, SERIALIZATION_FAILED, WRITE_FAILED, SHUTDOWN_TIMEOUT }
    private final Map<DropReason,LongAdder> droppedByReason=new EnumMap<>(DropReason.class);
    private volatile BlockingQueue<TrafficSample> queue;
    private int queueCapacity;
    private volatile boolean accepting;
    private Thread thread;
    public TrafficRecorder(TrafficRecordingProperties properties,ObjectMapper mapper,TrafficFileWriter writer) {
        this.properties=properties;this.mapper=mapper;this.writer=writer;
        for(var reason:DropReason.values()) droppedByReason.put(reason,new LongAdder());
    }
    @PostConstruct public void start() {
        if(!properties.isEnabled()) return;
        properties.validate();
        queueCapacity=properties.getQueueCapacity();
        queue=new ArrayBlockingQueue<>(queueCapacity);accepting=true;//对 volatile 字段的写 happens-before 后续对同一字段的读
        thread=new Thread(this::consume,"traffic-jsonl-writer");thread.setDaemon(true);thread.start();
    }
    public void record(String requestId,String userName,ChatEntity request,Instant receivedAt) {
        if(!accepting) return;
        if(ThreadLocalRandom.current().nextDouble()>=properties.getSampleRate()) {sampledOut.increment();return;}
        try {
            // 字符数是 JSON UTF-8 大小的保守下界；只拒绝肯定超限的样本，不做最坏情况倍乘。
            if(obviouslyTooLarge(request)) {drop(DropReason.RECORD_TOO_LARGE);return;}
            var body=new TrafficSample.RequestBody(request.getMessage(),request.getHistory()==null?List.of():request.getHistory());
            var sample=new TrafficSample(1,UUID.randomUUID().toString(),requestId,receivedAt.toString(),
                    actorKey(userName),writer.instanceId(),"POST","/chat/stream",body);
            boolean offered;
            synchronized(lifecycle) {
                if(!accepting) return;
                offered=queue.offer(sample);
                if(offered) accepted.increment();
            }
            if(!offered) drop(DropReason.QUEUE_FULL); // 日志和分类计数均在锁外。
        } catch(Exception ex) {drop(DropReason.SNAPSHOT_FAILED);}
    }
    private long recordLimit() {return Math.min(properties.getMaxRecordBytes(),properties.getMaxFileBytes()-1L);}
    private boolean obviouslyTooLarge(ChatEntity request) {
        long chars=request.getMessage().length();
        long limit=recordLimit();
        if(chars>limit) return true;
        if(request.getHistory()!=null) {
            for(var item:request.getHistory()) {
                chars+=(long)item.get("role").length()+item.get("content").length();
                if(chars>limit) return true;
            }
        }
        return false;
    }
    private String actorKey(String username) throws java.security.GeneralSecurityException {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getActorHashKey().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
    }
    private void consume() {
        try {
            while(accepting || !queue.isEmpty()) {
                try {
                    try {writer.maintenance();}catch(Exception ex){log.warn("流量文件清理失败，稍后写入仍会尝试；不影响聊天",ex);}
                    TrafficSample sample=queue.poll(100,TimeUnit.MILLISECONDS);
                    if(sample==null) continue;
                    byte[] json;
                    try {
                        // UTF-8、JSON 转义及元数据开销仍在后台精确校验。
                        json=mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(sample);
                    }catch(Exception ex){drop(DropReason.SERIALIZATION_FAILED);continue;}
                    if(json.length>recordLimit()) {drop(DropReason.RECORD_TOO_LARGE);continue;}
                    try {writer.write(json);written.increment();}
                    catch(Exception ex){drop(DropReason.WRITE_FAILED);}
                }catch(InterruptedException ex){if(!accepting) break;}
            }
        } finally {
            try{writer.close();}catch(Exception ex){log.warn("关闭流量文件失败",ex);}
            log.info("流量录制结束 stats={}",stats());
        }
    }
    private void drop(DropReason reason) {drop(reason,1);}
    private void drop(DropReason reason,long amount) {
        if(amount<=0) return;
        LongAdder counter=droppedByReason.get(reason);
        long before=counter.sum();counter.add(amount);dropped.add(amount);
        long count=counter.sum();
        if(before==0 || before/100!=count/100) {
            log.warn("流量样本丢弃 reason={} reasonCount={} dropped={}",reason,count,dropped.sum());
        }
    }
    /** 弱一致观测快照：队列大小和各 LongAdder 不是原子地读取。 */
    public Stats stats(){
        Map<DropReason,Long> reasons=new EnumMap<>(DropReason.class);
        droppedByReason.forEach((reason,counter)->reasons.put(reason,counter.sum()));
        var currentQueue=queue;
        return new Stats(accepted.sum(),written.sum(),dropped.sum(),sampledOut.sum(),
                currentQueue==null?0:currentQueue.size(),currentQueue==null?0:queueCapacity,Map.copyOf(reasons));
    }
    public record Stats(long accepted,long written,long dropped,long sampledOut,
                        int queueSize,int queueCapacity,Map<DropReason,Long> droppedByReason){}
    @PreDestroy @Override public void close() {
        synchronized(lifecycle){accepting=false;}
        if(thread==null) return;
        try {thread.join(properties.getShutdownTimeoutMs());}//有界等待排空
        catch(InterruptedException ex){Thread.currentThread().interrupt();}
        if(thread.isAlive()) {//超时兜底
            List<TrafficSample> remaining=new ArrayList<>();
            queue.drainTo(remaining);
            drop(DropReason.SHUTDOWN_TIMEOUT,remaining.size());
            thread.interrupt();
            log.warn("流量录制关闭超时，尚有样本可能丢失 remaining={}",remaining.size());
        }
    }
}
//请求线程只快照+非阻塞入队、独立 daemon 线程序列化写盘、分类计数丢弃、关闭时有界排空

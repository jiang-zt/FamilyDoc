package com.itzixi.traffic;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itzixi.bean.ChatEntity;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class TrafficRecorderTest {
    final ObjectMapper mapper=new ObjectMapper();
    TrafficRecordingProperties config() {
        var p=new TrafficRecordingProperties();p.setEnabled(true);p.setActorHashKey("test-key-at-least-sixteen-chars");return p;
    }
    ChatEntity input() {
        ChatEntity e=new ChatEntity();e.setMessage("你好\nquestion");
        var row=new HashMap<String,String>();row.put("role","user");row.put("content","old");row.put("Authorization","must-not-record");
        e.setHistory(new ArrayList<>(List.of(row,Map.of("role","assistant","content","answer"))));return e;
    }
    @Test void recordsImmutableOriginalBodyAndPseudonymousActorWithoutCredentials() throws Exception {
        mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        var writer=mock(TrafficFileWriter.class);when(writer.instanceId()).thenReturn("instance");
        var recorder=new TrafficRecorder(config(),mapper,writer);recorder.start();
        ChatEntity request=input();
        recorder.record("request-id","alice",request,Instant.parse("2026-10-28T01:02:03Z"));
        request.setMessage("changed");request.getHistory().get(0).put("content","changed");
        recorder.close();
        var captor=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(writer).write(captor.capture());
        assertThat(new String(captor.getValue(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("\n");
        var json=mapper.readTree(captor.getValue());
        assertThat(json.path("requestBody").path("message").asText()).isEqualTo("你好\nquestion");
        assertThat(json.path("requestBody").path("history").get(0).path("content").asText()).isEqualTo("old");
        assertThat(json.path("actorKey").asText()).hasSize(64);
        assertThat(json.path("recordedAt").asText()).isEqualTo("2026-10-28T01:02:03Z");
        assertThat(new String(captor.getValue(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("alice","Authorization","must-not-record");
        assertThat(recorder.stats().written()).isEqualTo(1);
    }
    @Test void disabledOrSampledOutDoesNotWrite() throws Exception {
        for(boolean enabled : new boolean[]{false,true}) {
            var p=config();p.setEnabled(enabled);p.setSampleRate(0);
            var writer=mock(TrafficFileWriter.class);
            var r=new TrafficRecorder(p,mapper,writer);r.start();r.record("id","alice",input(),Instant.now());r.close();
            verify(writer,never()).write(any());
        }
    }
    @Test void fullQueueDropsWithoutWaitingForDisk() throws Exception {
        var p=config();p.setQueueCapacity(1);
        var writer=mock(TrafficFileWriter.class);
        var entered=new CountDownLatch(1);var unblock=new CountDownLatch(1);
        doAnswer(i->{entered.countDown();unblock.await();return null;}).when(writer).write(any());
        var r=new TrafficRecorder(p,mapper,writer);r.start();
        try {
            r.record("one","a",input(),Instant.now());assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            r.record("two","a",input(),Instant.now());
            r.record("three","a",input(),Instant.now());
            assertThat(r.stats().dropped()).isEqualTo(1);
            assertThat(r.stats().queueSize()).isEqualTo(1);
            assertThat(r.stats().queueCapacity()).isEqualTo(1);
            assertThat(r.stats().droppedByReason().get(TrafficRecorder.DropReason.QUEUE_FULL)).isEqualTo(1L);
        } finally { unblock.countDown();r.close(); }
        assertThat(r.stats().written()).isEqualTo(2);
    }
    @Test void ioFailureAndOversizedRecordAreDroppedWithoutEscaping() throws Exception {
        var p=config();var writer=mock(TrafficFileWriter.class);
        doThrow(new java.io.IOException("disk full")).when(writer).write(any());
        var r=new TrafficRecorder(p,mapper,writer);r.start();
        r.record("id","a",input(),Instant.now());r.close();
        assertThat(r.stats().dropped()).isEqualTo(1);
        assertThat(r.stats().droppedByReason().get(TrafficRecorder.DropReason.WRITE_FAILED)).isEqualTo(1L);
        p=config();p.setMaxRecordBytes(10);writer=mock(TrafficFileWriter.class);
        r=new TrafficRecorder(p,mapper,writer);r.start();r.record("id","a",input(),Instant.now());r.close();
        assertThat(r.stats().dropped()).isEqualTo(1);verify(writer,never()).write(any());
    }
    @Test void obviousOversizeIsRejectedBeforeSnapshotAndSerialization() throws Exception {
        var p=config();p.setMaxRecordBytes(1000);
        var mapper=mock(ObjectMapper.class);var writer=mock(TrafficFileWriter.class);
        var r=new TrafficRecorder(p,mapper,writer);r.start();
        var body=input();body.setMessage("x".repeat(1001));
        r.record("id","a",body,Instant.now());r.close();
        verifyNoInteractions(mapper);verify(writer,never()).instanceId();verify(writer,never()).write(any());
        assertThat(r.stats().accepted()).isZero();
        assertThat(r.stats().droppedByReason().get(TrafficRecorder.DropReason.RECORD_TOO_LARGE)).isEqualTo(1L);
    }
    @Test void utf8SizeIsStillCheckedAfterSerializationAndValidAsciiIsNotOverestimated() throws Exception {
        for(boolean large:new boolean[]{false,true}) {
            var p=config();p.setMaxRecordBytes(1000);
            var writer=mock(TrafficFileWriter.class);var r=new TrafficRecorder(p,mapper,writer);r.start();
            var body=input();body.setMessage(large?"中".repeat(400):"x".repeat(400));
            r.record("id","a",body,Instant.now());r.close();
            assertThat(r.stats().accepted()).isEqualTo(1);
            if(large) {
                verify(writer,never()).write(any());
                assertThat(r.stats().droppedByReason().get(TrafficRecorder.DropReason.RECORD_TOO_LARGE)).isEqualTo(1L);
            } else assertThat(r.stats().written()).isEqualTo(1);
        }
    }
    @Test void shutdownCountsQueuedLossByReasonAndReturnsImmutableStats() throws Exception {
        var p=config();p.setQueueCapacity(2);p.setShutdownTimeoutMs(20);
        var entered=new CountDownLatch(1);var unblock=new CountDownLatch(1);
        var writer=mock(TrafficFileWriter.class);
        doAnswer(i->{
            entered.countDown();boolean waiting=true;
            while(waiting) {try {unblock.await();waiting=false;}catch(InterruptedException ignored){}}
            return null;
        }).when(writer).write(any());
        var r=new TrafficRecorder(p,mapper,writer);r.start();
        try {
            r.record("one","a",input(),Instant.now());assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            r.record("two","a",input(),Instant.now());r.record("three","a",input(),Instant.now());
            r.close();
            var stats=r.stats();assertThat(stats.queueSize()).isZero();
            assertThat(stats.dropped()).isEqualTo(2);
            assertThat(stats.droppedByReason().get(TrafficRecorder.DropReason.SHUTDOWN_TIMEOUT)).isEqualTo(2L);
            assertThatThrownBy(()->stats.droppedByReason().clear()).isInstanceOf(UnsupportedOperationException.class);
        } finally {unblock.countDown();r.close();}
    }
    @Test void serializationFailuresHaveSeparateReason() throws Exception {
        var p=config();var writer=mock(TrafficFileWriter.class);
        var broken=mock(ObjectMapper.class);when(broken.writer()).thenThrow(new IllegalStateException("serialize"));
        var r=new TrafficRecorder(p,broken,writer);r.start();r.record("id","a",input(),Instant.now());r.close();
        assertThat(r.stats().droppedByReason().get(TrafficRecorder.DropReason.SERIALIZATION_FAILED)).isEqualTo(1L);
        verify(writer,never()).write(any());
    }

    @Test void blockedDropLoggingDoesNotHoldLifecycleLock() throws Exception {
        var p=config();p.setQueueCapacity(1);
        var entered=new CountDownLatch(1);var releaseWriter=new CountDownLatch(1);
        var logEntered=new CountDownLatch(1);var releaseLog=new CountDownLatch(1);
        var writer=mock(TrafficFileWriter.class);
        doAnswer(i->{entered.countDown();releaseWriter.await();return null;}).when(writer).write(any());
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(TrafficRecorder.class);
        // 只阻塞目标日志，不用 AppenderBase 的全局 doAppend 锁干扰关闭日志。
        var appender=new ch.qos.logback.core.UnsynchronizedAppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                if(!event.getFormattedMessage().contains("reason=QUEUE_FULL")) return;
                logEntered.countDown();
                try {releaseLog.await(3,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}
            }
        };
        appender.start();logger.addAppender(appender);
        var r=new TrafficRecorder(p,mapper,writer);r.start();
        try(var executor=Executors.newFixedThreadPool(2)) {
            try {
                r.record("one","a",input(),Instant.now());assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
                r.record("two","a",input(),Instant.now());
                var dropped=executor.submit(()->r.record("three","a",input(),Instant.now()));
                assertThat(logEntered.await(1,TimeUnit.SECONDS)).isTrue();
                releaseWriter.countDown();
                executor.submit(r::close).get(1,TimeUnit.SECONDS);
                releaseLog.countDown();dropped.get(1,TimeUnit.SECONDS);
            } finally {releaseWriter.countDown();releaseLog.countDown();r.close();}
        } finally {logger.detachAppender(appender);appender.stop();}
    }

}

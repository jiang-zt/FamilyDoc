package com.itzixi.traffic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class TrafficFileWriterTest {
    @TempDir Path dir;
    TrafficRecordingProperties config() {var p=new TrafficRecordingProperties();p.setDirectory(dir.toString());p.setMaxFileBytes(20);p.setRetentionDays(2);return p;}
    @Test void writesOneLinePerSampleAndRollsByByteSize() throws Exception {
        var writer=new TrafficFileWriter(config(),Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"),ZoneOffset.UTC));
        writer.write("{\"text\":\"中文\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.write("{\"text\":\"more\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));writer.close();
        try(var files=Files.list(dir)) {
            var list=files.toList();assertThat(list).hasSize(2);
            for(var p:list) assertThat(Files.readAllLines(p)).hasSize(1);
        }
    }
    @Test void retentionOnlyDeletesExpiredTrafficFiles() throws Exception {
        Path expired=dir.resolve("chat-stream-2026-10-25-other-run-000001.jsonl");Files.writeString(expired,"old");
        Path recent=dir.resolve("chat-stream-2026-10-27-other-run-000001.jsonl");Files.writeString(recent,"recent");
        Path unrelated=dir.resolve("notes.jsonl");Files.writeString(unrelated,"keep");
        var writer=new TrafficFileWriter(config(),Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"),ZoneOffset.UTC));
        writer.maintenance();writer.close();
        assertThat(expired).doesNotExist();assertThat(recent).exists();assertThat(unrelated).exists();
    }
    @Test void idleCurrentFileExpiresWithoutAnotherRequest() throws Exception {
        var clock=new MutableClock();
        var writer=new TrafficFileWriter(config(),clock);
        writer.write("{}".getBytes());writer.maintenance();
        clock.now=Instant.parse("2026-11-02T00:00:00Z");writer.maintenance();writer.close();
        try(var files=Files.list(dir)){assertThat(files.count()).isZero();}
    }
    @Test void failedMaintenanceRetriesAfterBackoffOnSameDay() throws Exception {
        Path target=dir.resolve("capture");Files.writeString(target,"not a directory");
        var p=config();p.setDirectory(target.toString());
        var clock=new MutableClock();var writer=new TrafficFileWriter(p,clock);
        assertThatThrownBy(writer::maintenance).isInstanceOf(java.io.IOException.class);
        Files.delete(target);Files.createDirectory(target);
        Path old=target.resolve("chat-stream-2026-10-20-other-000001.jsonl");Files.writeString(old,"old");
        clock.now=clock.now.plusSeconds(61);writer.maintenance();writer.close();
        assertThat(old).doesNotExist();
    }
    @Test void rollsAtUtcMidnight() throws Exception {
        var clock=new MutableClock();var writer=new TrafficFileWriter(config(),clock);
        writer.write("{}".getBytes());clock.now=clock.now.plusSeconds(86400);writer.write("{}".getBytes());writer.close();
        try(var files=Files.list(dir)) {
            var names=files.map(p->p.getFileName().toString()).toList();
            assertThat(names).anyMatch(n->n.startsWith("chat-stream-2026-10-28-"));
            assertThat(names).anyMatch(n->n.startsWith("chat-stream-2026-10-29-"));
        }
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-28T00:00:00Z");
        public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
    }
    @Test void differentInstancesNeverShareAFile() throws Exception {
        var a=new TrafficFileWriter(config(),Clock.systemUTC());var b=new TrafficFileWriter(config(),Clock.systemUTC());
        assertThat(a.instanceId()).isNotEqualTo(b.instanceId());
        a.write("{}".getBytes());b.write("{}".getBytes());a.close();b.close();
        try(var files=Files.list(dir)) {assertThat(files.count()).isEqualTo(2);}
    }
}

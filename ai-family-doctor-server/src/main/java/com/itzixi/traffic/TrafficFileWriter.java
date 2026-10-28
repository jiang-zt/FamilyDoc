package com.itzixi.traffic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** 仅由录制单线程访问；不同进程的启动 UUID 保证不共享文件。日期和清理边界采用 UTC。 */
@Component
public class TrafficFileWriter implements AutoCloseable {
    private static final Pattern NAME=Pattern.compile("chat-stream-(\\d{4}-\\d{2}-\\d{2})-.+\\.jsonl");
    private final TrafficRecordingProperties properties;
    private final Clock clock;
    private final Path directory;
    private final String instanceId;
    private OutputStream output;
    private Path current;
    private LocalDate fileDate, maintenanceDate;
    private Instant nextMaintenanceAttempt=Instant.MIN;
    private long size, sequence;
    @Autowired public TrafficFileWriter(TrafficRecordingProperties properties) { this(properties,Clock.systemUTC()); }
    TrafficFileWriter(TrafficRecordingProperties properties, Clock clock) {
        this.properties=properties;this.clock=clock;this.directory=Path.of(properties.getDirectory());
        String label=Objects.toString(properties.getInstanceId(),"local").replaceAll("[^A-Za-z0-9_-]","_");
        if(label.length()>64) label=label.substring(0,64);
        instanceId=label+"-"+UUID.randomUUID();
    }
    public String instanceId() { return instanceId; }
    public void write(byte[] json) throws IOException {
        if (json.length+1L>properties.getMaxFileBytes()) throw new IOException("录制样本超过单文件限制");
        try {
            LocalDate today=LocalDate.now(clock);
            if(output==null || !today.equals(fileDate) || size+json.length+1>properties.getMaxFileBytes()) open(today);
            output.write(json);output.write('\n');output.flush();size+=json.length+1L;
        } catch(IOException ex) {
            try { close(); } catch(IOException closeEx) { ex.addSuppressed(closeEx); }
            throw ex;
        }
    }
    private void open(LocalDate today) throws IOException {
        close();Files.createDirectories(directory);
        current=directory.resolve("chat-stream-"+today+"-"+instanceId+"-"+String.format("%06d",++sequence)+".jsonl");
        FileAttribute<?>[] attrs=Files.getFileStore(directory).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
        output=new BufferedOutputStream(Channels.newOutputStream(Files.newByteChannel(current,
                EnumSet.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),attrs)));
        fileDate=today;size=0;
    }
    public void maintenance() throws IOException {
        LocalDate today=LocalDate.now(clock);
        Instant now=clock.instant();
        if(today.equals(maintenanceDate) || now.isBefore(nextMaintenanceAttempt)) return;
        try {
            Files.createDirectories(directory);
            LocalDate cutoff=today.minusDays(properties.getRetentionDays()-1L);
            if(output!=null && fileDate.isBefore(cutoff)) close();
            try(var files=Files.newDirectoryStream(directory,"chat-stream-*.jsonl")) {
                for(Path path:files) {
                    var matcher=NAME.matcher(path.getFileName().toString());
                    if(!matcher.matches() || (output!=null && path.equals(current))
                            || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        if(LocalDate.parse(matcher.group(1)).isBefore(cutoff)) Files.deleteIfExists(path);
                    } catch(java.time.format.DateTimeParseException ignored) { }
                }
            }
            maintenanceDate=today;
            nextMaintenanceAttempt=Instant.MIN;
        } catch(IOException ex) {
            nextMaintenanceAttempt=now.plusSeconds(60);
            throw ex;
        }
    }
    @Override public void close() throws IOException {
        if(output!=null) { var old=output;output=null;old.close(); }
    }
}

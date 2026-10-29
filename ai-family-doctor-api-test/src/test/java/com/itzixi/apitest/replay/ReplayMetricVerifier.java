package com.itzixi.apitest.replay;
import java.sql.*;
import java.time.Duration;
import java.util.*;

public class ReplayMetricVerifier {
    private final String url,password;
    public ReplayMetricVerifier(String url,String password){this.url=url;this.password=password;}
    private Connection connection() throws SQLException {
        Connection c=DriverManager.getConnection(url,"family_replay",password);
        if(!"ai_family_doctor_replay".equals(c.getCatalog())) {c.close();throw new ReplayFailure("WRONG_TARGET_DATABASE");}
        c.setReadOnly(true);return c;
    }
    public void preflight(){try(var c=connection()){}catch(SQLException ex){throw new ReplayFailure("REPLAY_DATABASE_UNAVAILABLE");}}
    public Map<String,Object> find(String requestId,Duration timeout) {
        long end=System.nanoTime()+timeout.toNanos();
        try(var c=connection();var statement=c.prepareStatement("SELECT request_id,user_name,question,status,error_code,accuracy_score,output_chars,context_message_count,context_chars,first_token_ms,total_ms FROM chat_metric WHERE request_id=?")) {
            statement.setString(1,requestId);
            do {
                try(var result=statement.executeQuery()) {
                    if(result.next()) {
                        Map<String,Object> row=new LinkedHashMap<>();
                        String[] columns={"request_id","user_name","question","status","error_code","accuracy_score","output_chars","context_message_count","context_chars","first_token_ms","total_ms"};
                        String[] keys={"requestId","userName","question","status","errorCode","accuracyScore","outputChars","contextMessageCount","contextChars","firstTokenMs","totalMs"};
                        for(int i=0;i<columns.length;i++)row.put(keys[i],result.getObject(columns[i]));
                        return row;
                    }
                }
                Thread.sleep(100);
            }while(System.nanoTime()<end);
            throw new ReplayFailure("METRIC_NOT_FOUND");
        }catch(SQLException ex){throw new ReplayFailure("METRIC_QUERY_FAILED");}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw new ReplayFailure("REPLAY_INTERRUPTED");}
    }
    public static void validate(Map<String,Object> row,ReplaySampleReader.Sample sample,ReplayHttpClient.StreamResult stream,String user) {
        if(!Objects.equals(row.get("requestId"),stream.requestId()) || !Objects.equals(row.get("userName"),user)
            || !Objects.equals(row.get("question"),sample.requestBody().path("message").asText())
            || !"SUCCESS".equals(row.get("status")) || !(row.get("accuracyScore") instanceof Number)
            || !(row.get("outputChars") instanceof Number chars) || chars.intValue()!=stream.answer().length()) {
            throw new ReplayFailure("METRIC_MISMATCH");
        }
    }
}

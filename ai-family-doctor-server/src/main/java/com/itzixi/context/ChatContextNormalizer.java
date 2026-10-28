package com.itzixi.context;
import com.itzixi.bean.ChatEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ChatContextNormalizer {
    @Value("${chat.context.max-turns:10}") private int maxTurns = 10;
    @Value("${chat.context.max-chars:12000}") private int maxChars = 12000;
    @Value("${chat.context.max-question-chars:8000}") private int maxQuestionChars = 8000;
    public PreparedChat prepare(ChatEntity request) {
        String question = request.getMessage();
        if (question == null || question.isBlank() || question.length() > maxQuestionChars) {
            throw bad("消息不能为空，且不能超过 " + maxQuestionChars + " 字符");
        }
        List<Map<String,String>> history = request.getHistory() == null ? List.of() : request.getHistory();
        if (history.size() > 200 || history.size() % 2 != 0) throw bad("历史必须是完整问答，最多 200 条消息");
        long inputChars = 0;
        //不做校验：大请求已进内存/已反序列化；脏数据可能让截断逻辑崩溃；无成本上限
        for (int i=0; i<history.size(); i++) {
            Map<String,String> item = history.get(i);
            String expected = i % 2 == 0 ? "user" : "assistant";
            if (item == null || !expected.equals(item.get("role")) || item.get("content") == null
                    || item.get("content").isBlank() || item.get("content").length() > 32000) {
                throw bad("历史须按 user、assistant 交替排列，且内容非空、单条不超过 32000 字符");
            }
            inputChars += item.get("content").length();
            if (inputChars > 100000) throw bad("提交的历史内容超过 100000 字符");
        }
        int start = history.size(), chars = 0, turns = 0;
        //截取最近的maxTurns轮数且char不超过maxChars,找到起点
        while (start >= 2 && turns < maxTurns) {
            int size = history.get(start-2).get("content").length() + history.get(start-1).get("content").length();
            if (chars + size > maxChars) break;
            chars += size; start -= 2; turns++;
        }
        //消息组装
        List<Map<String,String>> kept = new ArrayList<>();
        for (var item : history.subList(start, history.size())) {
            kept.add(Map.of("role",item.get("role"),"content",item.get("content")));
        }
        return new PreparedChat(question, kept);
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}

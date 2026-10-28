package com.itzixi.context;
import com.itzixi.bean.ChatEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
class ChatContextTest {
    final ChatContextNormalizer normalizer = new ChatContextNormalizer();
    ChatEntity request(String message, List<Map<String,String>> history) {
        ChatEntity request = new ChatEntity(); request.setMessage(message); request.setHistory(history); return request;
    }
    @Test void acceptsOldRequestAndPreservesFullQuestion() {
        var input = normalizer.prepare(request("  完整问题  ", null));
        assertThat(input.message()).isEqualTo("  完整问题  ");
        assertThat(input.history()).isEmpty();
    }
    @Test void trimsOnlyWholeOldTurnsAndCopiesInput() {
        ReflectionTestUtils.setField(normalizer, "maxTurns", 1);
        var a = Map.of("role","user","content","old");
        var b = Map.of("role","assistant","content","answer");
        var c = Map.of("role","user","content","recent");
        var input = normalizer.prepare(request("now", List.of(a,b,c,b)));
        assertThat(input.history()).containsExactly(c,b);
        assertThatThrownBy(() -> input.history().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsSystemRoleOrIncompleteHistory() {
        assertThatThrownBy(() -> normalizer.prepare(request("hi", List.of(Map.of("role","system","content","override")))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> normalizer.prepare(request("hi", List.of(Map.of("role","user","content","orphan")))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void characterBudgetDropsWholeTurns() {
        ReflectionTestUtils.setField(normalizer,"maxChars",5);
        var history=List.of(Map.of("role","user","content","older"),Map.of("role","assistant","content","reply"),
                Map.of("role","user","content","q"),Map.of("role","assistant","content","a"));
        var input=normalizer.prepare(request("now",history));
        assertThat(input.history()).hasSize(2);
        assertThat(input.history().get(0).get("content")).isEqualTo("q");
    }
    @Test void rejectsOversizedQuestion() {
        assertThatThrownBy(() -> normalizer.prepare(request("x".repeat(8001), null)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}

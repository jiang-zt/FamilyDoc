package com.itzixi.traffic;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
class TrafficArrivalFilterTest {
    @Test void stampsOnlyEnabledPostStreamBeforeDownstream() throws Exception {
        var p=new TrafficRecordingProperties();p.setEnabled(true);
        var filter=new TrafficArrivalFilter(p);
        var request=new MockHttpServletRequest("POST","/app/chat/stream");request.setContextPath("/app");
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->
            assertThat(req.getAttribute(TrafficRecorder.ARRIVAL_ATTRIBUTE)).isInstanceOf(java.time.Instant.class));
        var other=new MockHttpServletRequest("POST","/chat");
        filter.doFilter(other,new MockHttpServletResponse(),new MockFilterChain());
        assertThat(other.getAttribute(TrafficRecorder.ARRIVAL_ATTRIBUTE)).isNull();
        p.setEnabled(false);
        var disabled=new MockHttpServletRequest("POST","/chat/stream");
        filter.doFilter(disabled,new MockHttpServletResponse(),new MockFilterChain());
        assertThat(disabled.getAttribute(TrafficRecorder.ARRIVAL_ATTRIBUTE)).isNull();
    }
}

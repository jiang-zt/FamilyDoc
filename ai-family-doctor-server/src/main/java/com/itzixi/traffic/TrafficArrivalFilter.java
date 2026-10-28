package com.itzixi.traffic;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;
//给流量录制提供"请求何时到达"的时间戳
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+5)
public class TrafficArrivalFilter extends OncePerRequestFilter {
    private final TrafficRecordingProperties properties;
    public TrafficArrivalFilter(TrafficRecordingProperties properties){this.properties=properties;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(properties.isEnabled() && "POST".equals(request.getMethod())
                && (request.getContextPath()+"/chat/stream").equals(request.getRequestURI())) {
            request.setAttribute(TrafficRecorder.ARRIVAL_ATTRIBUTE,Instant.now());
        }
        chain.doFilter(request,response);
    }
}

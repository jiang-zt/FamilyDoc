package com.itzixi.context;
import java.util.List;
import java.util.Map;
//紧凑构造器（compact constructor）
/** 仅由入口校验器产生，避免异步线程读取可变请求对象。 */
//HttpServletRequest 和 ChatEntity 这类对象是请求作用域、可变的，而且请求线程结束后生命周期就结束
//存在数据竞争+生命周期问题
public record PreparedChat(String message, List<Map<String,String>> history) {
    public PreparedChat {
        //把传入的 List<Map<...>> 里每个 Map 复制成不可变的副本，再收集成新的 List
        history = history.stream().map(Map::copyOf).toList();//
    }
}

package com.itzixi;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * @ClassName ServiceLogAspect
 * @Author 风间影月
 * @Version 1.0
 * @Description ServiceLogAspect
 **/
@Slf4j
@Component
@Aspect
public class ServiceLogAspect {
    /**
     * @Description: 切面表达式
     *              *       返回任意类型，比如 void，object，list 等
     *              com.itzixi.service.impl  指定包名，要去具体切入切面的位置（某个java class所在的包位置）
     *              ..      可以匹配到当前包以及它的子包
     *              *       可以匹配当前包和子包下的java class
     *              .       无意义
     *              *       代表任意的方法名
     *              (..)    代表方法名的参数，这个参数是可以被传入的，也可以无参数
     * @Author 风间影月
     * @param
     * @return Object
     */
    @Around("execution(* com.itzixi.service.impl..*.*(..))")
    public Object recordTimeLog(ProceedingJoinPoint joinPoint) throws Throwable {
        long startNs = System.nanoTime();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        try {
            Object result = joinPoint.proceed();
            long costMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("event=service_call status=success class={} method={} costMs={}",
                    className, methodName, costMs);
            return result;
        } catch (Throwable throwable) {
            long costMs = (System.nanoTime() - startNs) / 1_000_000;
            log.error("event=service_call status=error class={} method={} costMs={} error={}",
                    className, methodName, costMs, throwable.getMessage(), throwable);
            throw throwable;
        }
    }

}

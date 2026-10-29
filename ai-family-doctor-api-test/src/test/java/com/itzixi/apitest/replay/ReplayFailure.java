package com.itzixi.apitest.replay;
/** 仅使用固定错误码，避免把 Token、正文或 JDBC 凭证写入失败报告。 */
public class ReplayFailure extends RuntimeException {
    private final String requestId;
    public ReplayFailure(String code){this(code,null);}
    public ReplayFailure(String code,String requestId){super(code);this.requestId=requestId;}
    public String requestId(){return requestId;}
}

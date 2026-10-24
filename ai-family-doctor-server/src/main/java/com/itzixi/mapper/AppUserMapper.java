package com.itzixi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itzixi.bean.AppUser;
//接口动态代理 → 
// MyBatis-Plus 自动生成 SQL 存进 MappedStatement → 
// SqlSession/Executor → 
// PreparedStatement 绑参 → 
// 连接池借 Connection → JDBC 驱动走 TCP/MySQL 协议
public interface AppUserMapper extends BaseMapper<AppUser> {
}

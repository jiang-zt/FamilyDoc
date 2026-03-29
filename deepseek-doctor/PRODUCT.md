# DeepSeek SpringAI Family Doctor - 产品文档

**项目名称：** deepseek-springai-family-doctor  
**版本：** 1.0-SNAPSHOT  
**作者：** 
**创建日期：** 2025 年 6 月  

---

## 📋 产品概述

**DeepSeek SpringAI Family Doctor** 是一个基于 **Spring AI + Ollama** 的家庭医生 AI 对话系统。它通过本地部署的 DeepSeek 模型为用户提供健康咨询、医疗问答等服务，支持流式输出和历史聊天记录保存。

### 核心价值
- 🏠 **家庭医生** - 为家庭成员提供 7x24 小时健康咨询
- 💬 **AI 对话** - 基于 DeepSeek 模型的智能医疗问答
- 📊 **流式输出** - SSE 实时推送，用户体验流畅
- 💾 **记录保存** - 聊天记录持久化到 MySQL 数据库

---

## 🏗️ 技术架构

### 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| **JDK** | 21 | Java 运行环境 |
| **Spring Boot** | 3.3.8 | 应用框架 |
| **Spring AI Ollama** | 1.0.3 | AI 模型集成 |
| **MyBatis-Plus** | 3.0.4 | ORM 框架 |
| **MySQL** | 8.0.33 | 数据库 |
| **Lombok** | 1.18.34 | 代码简化 |

### 架构分层

```
┌─────────────────────────────────────────┐
│           Controller Layer              │
│  OllamaController, SSEController        │
├─────────────────────────────────────────┤
│           Service Layer                 │
│  OllamaService, ChatRecordService       │
├─────────────────────────────────────────┤
│           Mapper/DAO Layer              │
│  ChatRecordMapper                       │
├─────────────────────────────────────────┤
│           Data Layer                    │
│  MySQL Database                         │
└─────────────────────────────────────────┘
         ↑
    Ollama (DeepSeek Model)
    http://127.0.0.1:11434
```

---

## 🔌 API 端点

### 基础健康检查
| 方法 | 端点 | 说明 |
|------|------|------|
| GET | `/hello/world` | 健康检查 |

### AI 对话接口

| 方法 | 端点 | 说明 | 响应类型 |
|------|------|------|----------|
| GET | `/ollama/ai/chat` | 同步 AI 对话（阻塞） | String |
| GET | `/ollama/ai/stream1` | 流式对话 v1 | Flux<ChatResponse> |
| GET | `/ollama/ai/stream2` | 流式对话 v2（手动收集） | List<String> |
| GET | `/ollama/ai/v2/chat` | Service 层同步对话 | String |
| GET | `/ollama/ai/v2/stream1` | Service 层流式 v1 | Flux<ChatResponse> |
| GET | `/ollama/ai/v2/stream2` | Service 层流式 v2 | List<String> |
| POST | `/ollama/ai/v3/doctor/stream` | **生产版本** - SSE 流式推送 | SSE |
| GET | `/ollama/getRecords` | 获取聊天记录 | List<ChatRecord> |

### 请求参数

**v3 版本（推荐）**
```json
POST /ollama/ai/v3/doctor/stream
Content-Type: application/json

{
  "currentUserName": "张三",
  "message": "我最近头痛，可能是什么原因？"
}
```

**聊天记录查询**
```
GET /ollama/getRecords?who=张三
```

---

## 📦 数据模型

### ChatEntity（请求实体）
```java
{
  "currentUserName": "String",  // 用户标识
  "message": "String"           // 用户提问内容
}
```

### ChatRecord（数据库记录）
```java
{
  "id": "String",              // 唯一 ID (雪花算法)
  "content": "String",         // 聊天内容
  "chatType": "String",        // USER / BOT
  "chatTime": "LocalDateTime", // 聊天时间
  "familyMember": "String"     // 家庭成员标识
}
```

### 数据库表结构
```sql
CREATE TABLE chat_record (
  id VARCHAR(255) PRIMARY KEY,
  content TEXT,
  chat_type VARCHAR(50),
  chat_time DATETIME,
  family_member VARCHAR(100)
);
```

CREATE TABLE chat_metric (
  id VARCHAR(255) PRIMARY KEY,
  user_name VARCHAR(100),
  question TEXT,
  model VARCHAR(100),
  prompt_version VARCHAR(50),
  first_token_ms BIGINT,
  total_ms BIGINT,
  avg_token_interval_ms DECIMAL(10,2),
  output_chars INT,
  output_tokens INT,
  accuracy_score INT,
  created_at DATETIME
);

CREATE TABLE app_user (
  id VARCHAR(255) PRIMARY KEY,
  username VARCHAR(100) UNIQUE,
  password_hash VARCHAR(255),
  created_at DATETIME,
  updated_at DATETIME
);

---

## 🚀 核心功能

### 1. AI 对话（同步）
- 调用 Ollama API 获取 DeepSeek 模型响应
- 阻塞式等待完整响应
- 适合简单问答场景

### 2. 流式输出（SSE）
- **SSE Server** 管理客户端连接
- 实时推送 AI 生成的每个 token
- 用户体验更流畅，无需等待完整响应
- 支持多人在线连接统计

### 3. 聊天记录持久化
- 用户消息保存为 `ChatTypeEnum.USER`
- AI 回复保存为 `ChatTypeEnum.BOT`
- 支持按用户查询历史对话

### 4. 连接管理
- SSE 连接超时/错误/完成回调
- 在线人数统计（AtomicInteger）
- 自动清理失效连接

---

## 🔧 配置文件

### application.yml（主配置）
```yaml
spring:
  application:
    name: deepseek-doctor
  profiles:
    active: dev

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      id-type: assign_id
      update-strategy: not_empty
  mapper-locations: classpath*:/mappers/*.xml
```

### 环境配置
- `application-dev.yml` - 开发环境
- `application-prod.yml` - 生产环境

### Ollama 配置（需启用）
```yaml
spring:
  ai:
    ollama:
      base-url: http://127.0.0.1:11434
      chat:
        model: my-doctor:1.0.1.Release
```

---

## 📁 项目结构

```
deepseek-doctor/
├── src/main/java/com/itzixi/
│   ├── Application.java          # 启动类
│   ├── ChatConfig.java           # 聊天配置
│   ├── ServiceLogAspect.java     # 服务日志切面
│   ├── bean/
│   │   ├── ChatEntity.java       # 请求实体
│   │   └── ChatRecord.java       # 数据库实体
│   ├── controller/
│   │   ├── HelloController.java  # 健康检查
│   │   ├── OllamaController.java # AI 对话控制器
│   │   └── SSEController.java    # SSE 控制器
│   ├── mapper/
│   │   └── ChatRecordMapper.java # DAO 接口
│   ├── service/
│   │   ├── ChatRecordService.java
│   │   ├── OllamaService.java
│   │   └── impl/
│   │       ├── ChatRecordServiceImpl.java
│   │       └── OllamaServiceImpl.java
│   └── utils/
│       ├── ChatTypeEnum.java     # 聊天类型枚举
│       ├── SSEMsgType.java       # SSE 消息类型
│       └── SSEServer.java        # SSE 服务器
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── mappers/ChatRecordMapper.xml
└── pom.xml
```

---

## 🛠️ 开发与部署

### 开发环境要求
- JDK 21
- Maven
- MySQL 8.0+
- Ollama + DeepSeek 模型

### 启动命令
```bash
# 开发环境
mvn spring-boot:run

# 打包
mvn clean package

# 运行 JAR
java -jar target/deepseek-doctor.jar
```

### 部署资源
项目包含以下部署文档：
- `docker-install-steps` - Docker 安装步骤
- `docker-mysql8` - MySQL 8 Docker 配置
- `nginx-config` - Nginx 配置
- `run-jar-in-java` - JAR 运行指南
- `my_doctor_0_1`, `my_doctor_0_2` - 模型版本说明

---

## 🎯 使用场景

### 家庭医生咨询
- 症状初步判断
- 用药建议查询
- 健康知识科普
- 就医指导

### 技术演示
- Spring AI 集成示例
- SSE 流式输出实践
- MyBatis-Plus 使用

---

## ⚠️ 注意事项

1. **Ollama 服务** - 需要本地运行 Ollama 并加载 DeepSeek 模型
2. **数据库初始化** - 需要预先创建 chat_record 表
3. **SSE 连接** - 浏览器需支持 EventSource
4. **生产配置** - 生产环境需配置正确的数据库连接和 Ollama 地址

---

## 📝 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0-SNAPSHOT | 2025-06 | 初始版本 |

---

**文档生成：** 麻辣大龙虾 🦞  
**生成时间：** 2026-03-11 22:23 GMT+8

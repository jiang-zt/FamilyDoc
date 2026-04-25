# DSSpringAIFamilyDoctor

一个面向家庭健康问答场景的 AI 对话项目，采用前后端分离架构，支持 JWT 鉴权、同步问答、SSE 流式回复、聊天记录持久化与会话指标采集。

## 项目功能

- 用户注册、登录与 JWT 鉴权
- 同步聊天接口：`POST /ollama/chat`
- 流式聊天接口：`POST /ollama/chat/stream`
- SSE 长连接通道：`GET /sse/connect`
- 聊天记录查询与清空：`GET /ollama/records`、`DELETE /ollama/records`
- 会话指标落库：`firstTokenMs`、`totalMs`、`avgTokenIntervalMs`、`outputTokens`
- Mock 模式联调与压测支持

## 技术栈

- 后端：Java 21、Spring Boot 3.3.x、MyBatis-Plus、MySQL 8.x
- 模型接入：DashScope Java SDK、SSE (`SseEmitter`)
- 前端：Vue2、Axios、静态页面
- 测试：JMeter、Shell 脚本、独立 API 测试工程 `ai-deepseek-api-test`

## 数据库结构

核心表如下：

### `app_user`

- `id`
- `username`
- `password_hash`
- `created_at`
- `updated_at`

### `chat_record`

- `id`
- `content`
- `chat_type`
- `chat_time`
- `family_member`

### `chat_metric`

- `id`
- `user_name`
- `question`
- `model`
- `prompt_version`
- `first_token_ms`
- `total_ms`
- `avg_token_interval_ms`
- `output_chars`
- `output_tokens`
- `accuracy_score`
- `created_at`

其中 `chat_metric` 用于记录会话级性能指标，便于排查首 token 延迟、总耗时和输出稳定性。

## 启动方式

### 1. 准备数据库

1. 创建数据库：`deepseek_doctor`
2. 执行表结构，参考 `deepseek-doctor/PRODUCT.md`
3. 执行迁移脚本：

```sql
ALTER TABLE chat_metric
ADD COLUMN avg_token_interval_ms DECIMAL(10,2) NULL COMMENT '相邻输出 token（或 chunk）平均到达间隔（毫秒）'
AFTER total_ms;
```

### 2. 配置环境变量

```bash
export DASHSCOPE_API_KEY=your_api_key
export DASHSCOPE_MODEL=qwen3-235b-a22b
export DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/api/v1
```

如需开启 Mock 模式：

```bash
export DASHSCOPE_MOCK_ENABLED=true
export DASHSCOPE_MOCK_RESPONSE="这是本地压测 Mock 回复。"
export DASHSCOPE_MOCK_FIRST_TOKEN_DELAY_MS=120
export DASHSCOPE_MOCK_CHUNK_DELAY_MS=60
export DASHSCOPE_MOCK_CHUNK_SIZE=8
```

### 3. 启动后端

```bash
cd deepseek-doctor
mvn spring-boot:run
```

或：

```bash
cd deepseek-doctor
./run.sh
```

默认端口：`8080`

### 4. 启动前端

`family-doctor` 为静态页面，使用任意静态服务器启动到 `5500` 端口即可。

示例访问地址：

```text
http://<your-host>:5500/pages/entry.html
```

## 常见问题

### 1. 模型调用返回 `401 Unauthorized`

- 检查 `DASHSCOPE_API_KEY` 是否正确
- 检查 `DASHSCOPE_MODEL` 是否具备访问权限
- 检查 `DASHSCOPE_BASE_URL` 是否与当前网关一致

### 2. SSE 无法正常收到流式消息

- 确认是否先建立 `GET /sse/connect`
- 确认 `token` 是否有效、`userId` 是否与当前登录用户一致
- 检查浏览器 `Network` 面板中 SSE 请求是否保持 `pending`

### 3. 压测时看不到真实模型性能

- 开启 Mock 模式时，测的是服务端链路稳定性，不是真实模型推理性能
- 如需测试模型真实表现，请关闭 Mock，结合后端 `chat_metric` 指标与抓包结果一起观察

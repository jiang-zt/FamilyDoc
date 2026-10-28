# AI Family Doctor

一个面向家庭健康问答场景的 AI 对话项目，采用前后端分离架构，支持 JWT 鉴权、同步问答、SSE 流式回复、客户端上下文与问题评分指标采集。

## 项目功能

- 用户注册、登录与 JWT 鉴权
- 同步聊天接口：`POST /chat`
- 流式聊天接口：`POST /chat/stream`
- 聊天每轮独立 SSE：`POST /chat/stream`，JSON 事件 `delta / done / failed`，完成后关闭响应
- 独立 SSE 演示通道：`GET /sse/connect`（聊天页面不再使用）
- 历史由客户端页面维护，每次请求携带 `history`；仅成功完成的问答进入上下文，刷新或清空本地对话后丢失
- `GET/DELETE /chat/records` 仅保留旧数据兼容，聊天页面不再调用
- 问题全文、请求标识、SUCCESS/FAILED/CANCELLED/TIMEOUT 状态、上下文规模、评分与性能指标保存到 `chat_metric`
- 会话指标落库：`firstTokenMs`、`totalMs`、`avgTokenIntervalMs`、`outputTokens`
- Mock 模式联调与压测支持

## 技术栈

- 后端：Java 21、Spring Boot 3.3.x、MyBatis-Plus、MySQL 8.x
- 模型接入：DashScope Java SDK、SSE (`SseEmitter`)
- 前端：Vue2、Axios、静态页面
- 测试：JMeter、Shell 脚本、独立 API 测试工程 `ai-family-doctor-api-test`

## 测试模块

- `ai-family-doctor-server/tools/llm_smoke.sh`：端到端冒烟测试，覆盖登录、同步聊天和独立流式 POST
- `ai-family-doctor-server/tools/jmeter/llm_chat_test.jmx`：JMeter 并发压测脚本，主要用于非流式接口
- `ai-family-doctor-api-test`：独立接口测试工程，用于认证、聊天、SSE 等接口回归验证

## 数据库结构

核心表如下：

### `app_user`

- `username`
- `password_hash`

### `chat_record`

- `content`
- `chat_type`
- `chat_time`

### `chat_metric`

- `model`
- `first_token_ms`
- `total_ms`
- `avg_token_interval_ms`
- `output_tokens`

其中 `chat_metric` 用于记录模型延迟时间和输出指标，例如首 token 延迟、总耗时和平均输出间隔。

## 启动方式

### 1. 准备数据库

1. 创建数据库：`deepseek_doctor`
2. 新库执行初始化脚本：

```text
ai-family-doctor-server/src/main/resources/sql/20260321_initial_schema.sql
```

3. 旧库按需执行迁移脚本，脚本位于 `ai-family-doctor-server/src/main/resources/sql/`

```sql
ALTER TABLE chat_metric
ADD COLUMN avg_token_interval_ms DECIMAL(10,2) NULL COMMENT '相邻输出 token（或 chunk）平均到达间隔（毫秒）'
AFTER total_ms;
```

当前评分明细字段迁移脚本：

```text
ai-family-doctor-server/src/main/resources/sql/20260429_add_score_detail_columns.sql
```

已有库启用客户端上下文版本前，需执行一次：

```text
ai-family-doctor-server/src/main/resources/sql/20261027_client_context_metrics.sql
```

新库初始化脚本已包含这些字段，不重复执行本次 ALTER。旧聊天记录和旧指标不删除；旧指标新增字段保持 NULL。

聊天请求示例：

```json
{"message":"继续追问","history":[{"role":"user","content":"上一轮问题"},{"role":"assistant","content":"上一轮完整回答"}]}
```

省略 history 等同于单轮问答。服务端只接受完整的 user/assistant 对，系统提示词由服务端添加。done 仅代表生成完成；问题与评分指标保存失败会记录服务端错误，不把成功回答改判失败。无持久队列时，数据库异常或进程退出仍可能导致指标丢失。

评分优先匹配当前问题规则，未命中时参考最近用户历史，并在评分明细标注局限；不验证指代关系或跨轮医学一致性。未命中专用规则的回答标记为需复核。

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
cd ai-family-doctor-server
mvn spring-boot:run
```

或：

```bash
cd ai-family-doctor-server
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

- 聊天页面通过 fetch POST 直接读取 `/chat/stream`，无需预先建立连接
- 确认 Authorization 中的 token 有效，并检查事件流响应类型
- 请求应在生成期间保持 pending，收到 `done` 或 `failed` 后关闭；无终止事件的断流表示结果未确认
- 异步线程池和超时参数位于 `chat.stream`，线程池繁忙时返回 503

### 3. 压测时看不到真实模型性能

- 开启 Mock 模式时，测的是服务端链路稳定性，不是真实模型推理性能
- 如需测试模型真实表现，请关闭 Mock，结合后端 `chat_metric` 指标与抓包结果一起观察

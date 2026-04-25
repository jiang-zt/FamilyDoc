# DSSpringAIFamilyDoctor

一个面向家庭健康问答场景的 AI 对话项目，采用前后端分离架构，支持 JWT 鉴权、SSE 流式回复、聊天记录持久化，以及会话级性能指标采集。

## 项目概览

| 模块 | 说明 |
| --- | --- |
| `deepseek-doctor` | Spring Boot 后端，负责认证、对话接口、SSE 推送、会话指标落库 |
| `family-doctor` | 静态前端页面，基于 Vue2 + Axios 实现聊天交互 |
| `ai-deepseek-api-test` | 独立接口测试工程，用于认证、聊天、SSE 等 API 回归验证 |

后端当前基于 DashScope Java SDK 接入模型能力，并提供同步问答与流式推送两种交互方式。

## 核心能力

- JWT 登录、注册与身份校验
- 同步聊天接口：`POST /ollama/chat`
- 流式聊天触发接口：`POST /ollama/chat/stream`
- SSE 长连接通道：`GET /sse/connect`
- 聊天记录查询与清空：`GET /ollama/records`、`DELETE /ollama/records`
- 会话指标采集：`firstTokenMs`、`totalMs`、`avgTokenIntervalMs`、`outputTokens`
- Mock 模式压测与联调支持

## 技术栈

- Java 21
- Spring Boot 3.3.x
- MyBatis-Plus
- MySQL 8.x
- DashScope Java SDK
- SSE (`SseEmitter`)
- Vue2 + Axios

## 目录结构

```text
deepseek-springai-family-doctor/
├── deepseek-doctor/      # Java 后端服务
├── family-doctor/        # 前端静态页面
├── ai-deepseek-api-test/ # API 自动化测试工程
└── docs/                 # 演示素材与补充文档
```

## 演示素材

如果需要在 GitHub 首页展示演示视频，可将文件放到仓库根目录的 `docs/` 下：

- `docs/demo-cover.png`
- `docs/demo.mp4`

示例展示方式：

[![观看演示](https://raw.githubusercontent.com/jiang-zt/FamilyDoc/main/docs/demo-cover.png)](https://github.com/jiang-zt/FamilyDoc/raw/refs/heads/main/docs/demo.mp4)

## 环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x

## 快速开始

### 1. 准备数据库

1. 创建数据库：`deepseek_doctor`
2. 执行初始化表结构，参考 `deepseek-doctor/PRODUCT.md`
3. 执行指标字段迁移脚本：

```sql
ALTER TABLE chat_metric
ADD COLUMN avg_token_interval_ms DECIMAL(10,2) NULL COMMENT '相邻输出 token（或 chunk）平均到达间隔（毫秒）'
AFTER total_ms;
```

对应脚本文件：`deepseek-doctor/src/main/resources/sql/20260322_add_avg_token_interval_ms.sql`

### 2. 配置后端环境变量

模型调用相关：

```bash
export DASHSCOPE_API_KEY=your_api_key
export DASHSCOPE_MODEL=qwen3-235b-a22b
export DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/api/v1
```

压测或联调时可开启 Mock 模式：

```bash
export DASHSCOPE_MOCK_ENABLED=true
export DASHSCOPE_MOCK_RESPONSE="这是本地压测 Mock 回复。"
export DASHSCOPE_MOCK_FIRST_TOKEN_DELAY_MS=120
export DASHSCOPE_MOCK_CHUNK_DELAY_MS=60
export DASHSCOPE_MOCK_CHUNK_SIZE=8
```

主配置文件：

- `deepseek-doctor/src/main/resources/application.yml`
- `deepseek-doctor/src/main/resources/application-dev.yml`

### 3. 启动后端

```bash
cd deepseek-doctor
mvn spring-boot:run
```

或使用脚本：

```bash
cd deepseek-doctor
./run.sh
```

默认端口为 `8080`。

### 4. 启动前端

`family-doctor` 为静态页面，推荐使用任意静态服务器启动到 `5500` 端口，例如 VSCode Live Server。

示例访问地址：

```text
http://<your-host>:5500/pages/entry.html
```

前端默认会按当前访问 host 拼接后端地址 `http://<host>:8080`。

## 接口与交互说明

### 同步接口

- `POST /auth/login`
- `POST /ollama/chat`
- `GET /ollama/records`
- `DELETE /ollama/records`

### 流式接口

1. 先建立 SSE 连接：`GET /sse/connect?userId=...&token=...`
2. 再调用流式触发接口：`POST /ollama/chat/stream`
3. 前端通过 SSE 接收 `add`、`finish` 等事件

## 测试与压测

后端已提供一组可直接执行的测试脚本：

```bash
cd deepseek-doctor
chmod +x tools/start_mock.sh tools/llm_smoke.sh tools/start_with_proxy.sh
```

- `./tools/start_mock.sh`
  使用 Mock 模式启动后端，适合稳定性测试与联调
- `./tools/llm_smoke.sh`
  端到端冒烟验证，覆盖登录、同步聊天、SSE 建连、流式触发和事件检查
- `PROXY_HOST=127.0.0.1 PROXY_PORT=8888 ./tools/start_with_proxy.sh`
  让 JVM 通过代理访问模型平台，便于抓包排查

### JMeter 使用说明

JMeter 用例位于：

- `deepseek-doctor/tools/jmeter/llm_chat_test.jmx`
- `deepseek-doctor/tools/jmeter/users.csv`

当前 JMeter 计划主要用于非流式接口并发压测，关注：

- `Error %`
- `Throughput`
- `Avg / P95 / P99`

命令行执行方式：

```bash
jmeter -n \
  -t tools/jmeter/llm_chat_test.jmx \
  -l tools/jmeter/result.jtl \
  -e -o tools/jmeter/report
```

报告入口：`deepseek-doctor/tools/jmeter/report/index.html`

### 流式链路指标

SSE 长连接链路不适合仅依赖 JMeter 直接统计首 token 时延，因此项目在后端采集并持久化以下指标：

- `firstTokenMs`
- `totalMs`
- `avgTokenIntervalMs`
- `outputChars`
- `outputTokens`

建议结合以下方式综合观察：

- `tools/llm_smoke.sh` 验证 SSE 事件序列是否正常
- 后端日志中的 `会话Metric`
- `chat_metric` 表中的会话指标记录
- 代理抓包中的 TTFB 表现

详细说明见 `deepseek-doctor/LLM_TESTING_GUIDE.md`。

## 常见问题

### 1. 模型调用返回 `401 Unauthorized`

优先检查：

- `DASHSCOPE_API_KEY` 是否正确
- `DASHSCOPE_MODEL` 是否具备访问权限
- `DASHSCOPE_BASE_URL` 是否与当前网关一致

### 2. SSE 无法正常收到流式消息

优先检查：

- 是否已先建立 `GET /sse/connect`
- `token` 是否有效，`userId` 是否与当前登录用户一致
- 浏览器 `Network` 面板中 SSE 请求是否保持 `pending`

## 相关文档

- `deepseek-doctor/LLM_TESTING_GUIDE.md`
- `deepseek-doctor/PRODUCT.md`
- `deepseek-doctor/README.md`
- `ai-deepseek-api-test/README.md`

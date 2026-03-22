# LLM 模块实操测试（本项目版）

这份文档只讲可直接执行的步骤，不讲面试模板。

## 1. 先把后端切到可压测的 Mock 模式

在 `deepseek-doctor` 目录执行：

```bash
chmod +x tools/start_mock.sh tools/llm_smoke.sh tools/start_with_proxy.sh
./tools/start_mock.sh
```

说明：
- 这个脚本会自动设置 `DASHSCOPE_MOCK_ENABLED=true`。
- 这样不会真实调用模型平台，适合先做稳定性/并发测试。
- Mock 流式延迟可通过环境变量调整（见 `application.yml` 中 `dashscope.mock.*`）。

## 2. 先跑一遍端到端冒烟（含 SSE）

```bash
./tools/llm_smoke.sh http://127.0.0.1:8080 jiangzhuotong 12345678 "请给我三条睡眠建议"
```

这个脚本会自动做 5 件事：
1. `POST /auth/login` 拿 token  
2. `POST /ollama/chat` 做非流式验证  
3. 建立 `GET /sse/connect?userId=...`  
4. `POST /ollama/chat/stream` 触发流式生成  
5. 打印 SSE 最近事件（`add` / `finish`）

如果这里不通，就先别上 JMeter，先修通链路。

## 3. 用 JMeter 做非流式并发压测

### 3.1 GUI 跑法（推荐先用这个）

1. 安装并启动：

```bash
brew install jmeter
jmeter
```

2. 在 JMeter 里导入：
- 文件：`tools/jmeter/llm_chat_test.jmx`
- 数据：`tools/jmeter/users.csv`

3. 调整并发参数（`Thread Group -> ChatUsers`）：
- `Number of Threads`: 并发用户数（如 10/30/50）
- `Ramp-up period`: 拉起时长（如 10 秒）
- `Loop Count`: 每个用户循环次数（如 5）

4. 点击运行后，看：
- `Summary Report` 的 `Error % / Throughput / Avg`
- 失败样本可在 `View Results Tree`（建议只在小并发时打开）

### 3.2 CLI 跑法（可重复）

```bash
jmeter -n \
  -t tools/jmeter/llm_chat_test.jmx \
  -l tools/jmeter/result.jtl \
  -e -o tools/jmeter/report
```

跑完打开 `tools/jmeter/report/index.html` 看图表。

## 4. 流式链路（SSE）怎么测

JMeter 对 SSE 长连接不友好，这个项目建议：

1. 用 `tools/llm_smoke.sh` 持续做流式功能验证（是否有 `add`、是否有 `finish`）。  
2. 用 JMeter 压 `POST /ollama/chat/stream` 的触发接口看吞吐与错误率。  
3. 同时看后端日志里的每会话 Metric（`会话Metric: ...`）确认 `firstTokenMs`、`totalMs`、`avgTokenIntervalMs` 是否符合预期。

## 5. 抓包（两条链路）

## 5.1 前端 -> 后端（浏览器抓包）

1. 打开页面 `http://<你的IP>:5500/pages/chat-doctor.html`。  
2. F12 -> `Network`。  
3. 过滤关键请求：
- `/auth/login`
- `/sse/connect`
- `/ollama/chat/stream`

重点看：
- `Authorization` 是否带上
- `/sse/connect` 是否保持 pending 状态
- SSE 消息序列是否 `add...add...finish`

## 5.2 后端 -> 模型平台（代理抓包）

1. 启动抓包代理（例如 Charles / mitmproxy，端口 8888）。  
2. 用项目脚本让 JVM 走代理：

```bash
PROXY_HOST=127.0.0.1 PROXY_PORT=8888 ./tools/start_with_proxy.sh
```

3. 在抓包工具中过滤域名：
- `dashscope.aliyuncs.com`（官方）
- 或你当前网关域名（如 `api.scnet.cn`）

重点看：
- 请求路径是否 `/api/llm/v1/chat/completions` 或 DashScope 对应路径
- 返回码是否 `200`（`401` 基本就是 key/权限/模型名问题）
- TTFB 是否突增（可对照后端 `firstTokenMs`）

## 6. 重点判定标准（面向 C 端）

- 功能：登录后能稳定收到 `add` + `finish`，聊天记录可回放。  
- 性能：并发上升时 `Error %` 可控，`firstTokenMs/avgTokenIntervalMs/totalMs` 不异常飙升。  
- 稳定：断网、401、超时场景能优雅返回“模型调用失败”，前端不崩。  
- 观测：每会话有 metric，方便定位是网络、模型还是业务层瓶颈。

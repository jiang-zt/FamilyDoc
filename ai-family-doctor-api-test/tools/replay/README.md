# 本机单条流量回放

当前只实现独立 Mock 环境和单条闭环，无批量、倍速、自动重试或自动清理。

## 1. 初始化独立库

在项目根目录执行：

```bash
python3 tools/replay/init_db.py --use-dev-credentials
```

该参数明确允许读取 application-dev.yml 中的本机凭证，不显示、不保存管理员密码。也可不带参数交互输入管理员密码。脚本只创建 `ai_family_doctor_replay`、专用 `family_replay` 账号及表，授予该库 SELECT/INSERT/UPDATE/DELETE，不重置 root、不改变开发库。已有同名库/账号但缺少 .env.replay 时拒绝接管。

随机测试库密码和独立 JWT 密钥保存到根目录 `.env.replay`，权限 0600，Git 忽略。脚本不会重置已存在的账号密码；MySQL 密码策略失败且账号尚未创建时，可以修正配置后再次运行。密码要求比 MEDIUM 更严格的本机环境需使用满足本机策略的值。

## 2. 启动

```bash
bash tools/replay/start.sh
```

前台运行，只监听 127.0.0.1:18080；Ctrl+C 停止。可通过 .env.replay 的 REPLAY_PORT 修改端口。强制使用 replay profile、专用账号/库、Mock 模型并关闭录制；保护组件会验证实际数据库名和账号，不符合则启动失败。仅 replay profile 提供 `/internal/replay/environment` 标记端点，不暴露密码。

## 3. 单条回放（另一个终端）

```bash
# 默认使用仓库自带演示样本
bash tools/replay/run_one.sh

# 使用已关闭/复制完整的录制文件：默认第一条，或指定 sampleId
bash tools/replay/run_one.sh /绝对路径/recording.jsonl
bash tools/replay/run_one.sh /绝对路径/recording.jsonl sampleId
```

回放器先验证目标标记和独立库，然后创建 8 位测试用户名、注册并重新登录，提交原始 requestBody，读取完整 SSE，并按新 requestId 核对问题全文、状态、输出长度、评分。分数是否达到质量阈值不决定链路是否通过。回放源 requestId 只保留在报告，不复用到目标实例。

报告：`ai-family-doctor-api-test/target/replay/<runId>.json`。PASS 代表 done 与 SUCCESS 指标、问题和输出长度核对通过，评分值已存在；不代表 Mock 回答具有医学正确性。报告不复制原始正文、回答或 Token。FAILED/断流/超时/缺失指标会生成 FAIL 报告，测试进程返回失败。样本必须是 schemaVersion=1、POST /chat/stream，文件行必须以换行结束；读到坏行直接失败，不静默跳过。

入口 ReplayRunTest 只有显式设置 -Dreplay.enabled=true 才运行，不继承 ApiTestBase，也不触发原有开发库自动清理。测试用户及指标保留供人工核查；如需清理，应根据报告中的 testUser 精确删除，且只能操作回放库。

## 代码阅读顺序

1. `application-replay.yml`、`ReplayEnvironment`：隔离条件和启动保护。
2. `init_db.py`、`schema.sql`：新库与受限账号，凭证写入方式。
3. `start.sh`、`run_one.sh`：执行入口。
4. `ReplayRunTest`、`SingleReplay`：显式启用与单条编排。
5. `ReplaySampleReader`：输入格式校验及单条选择。
6. `ReplayHttpClient`：测试登录、SSE 解析、整轮截止时间与资源关闭。
7. `ReplayMetricVerifier`：只读查询和指标核对。

前置条件：Java 21+、Maven、Python 3、mysql 客户端及运行中的本机 MySQL。不新增运行依赖，不需要启动前端。

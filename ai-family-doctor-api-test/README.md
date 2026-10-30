# AI Family Doctor API Test

## 1. 作用

项目的接口验证与回归测试模块，用于检查账号、聊天和流式响应等功能是否符合预期，并通过隔离回放复现请求，辅助评估功能修改的影响。

## 2. 负责工作

- 验证注册、登录、身份认证、权限限制及接口参数校验。
- 校验手写 OpenAPI 合约文件，使用公共评分规则开展医疗问答评测。
- 读取录制的 JSONL 样本，在独立 Mock 环境中回放单条流式请求，核对 SSE 响应与数据库指标，生成结果报告。

回放通过表示请求链路与指标核对通过，不代表回答具有医学正确性；当前尚未支持批量回放和负载模拟。

## 3. 结构介绍

| 内容 | 职责 |
| --- | --- |
| `contracts/` | 保存手写 OpenAPI 接口合约 |
| `src/test/java/com/itzixi/apitest/` | 账号、聊天、旧 SSE 演示接口及合约文件测试，医疗问答规则评测 |
| `src/test/java/com/itzixi/apitest/support/` | 公共测试配置、认证辅助、SSE 客户端和测试数据清理 |
| `src/test/java/com/itzixi/apitest/replay/` | 样本校验、单条回放编排、流式响应解析、指标核对与报告生成 |
| `src/test/resources/replay/` | 合成的回放演示样本 |
| `tools/replay/` | 独立回放环境所需的数据库结构、环境准备及回放入口脚本 |

医疗问答评分规则与评测用例由公共模块 `medical-answer-scoring` 统一维护。

# DeepSeek DashScope 家庭医生项目知识库

本项是一个基于 Spring Boot 架构的 AI 智能诊疗辅助系统，集成 DeepSeek (通过 Dashscope) 大模型提供医疗问答服务，并利用 SSE 技术实现流式响应。

## 1. 技术栈概览
- **核心框架**: Spring Boot 3.x
- **AI SDK**: Alibaba Dashscope Java SDK (兼容 OpenAI 协议)
- **响应式编程**: Project Reactor (Flux), RxJava (Flowable)
- **实时通信**: Server-Sent Events (SSE)
- **安全认证**: Auth0 JWT
- **并发管理**: ConcurrentHashMap, AtomicInteger

## 2. 核心模块与实现原理

### 2.1 AI 交互逻辑 (`DashscopeChatClient`)
- **实现方式**: 封装了 Dashscope 的 `Generation` 类。
- **双模式支持**: 
    - **同步调用**: 使用 `generation.call` 返回完整文本。
    - **流式推送**: 返回 `Flux<String>`，通过 `generation.streamCall` 将大模型的增量输出转化为响应式流。
- **Mock 机制**: 内置模拟开关 (`mockEnabled`)，在非联网环境下可模拟打字机效果的流式输出，便于前端调试。

### 2.2 实时通信架构 (`SSEServer`)
- **连接管理**: 使用 `ConcurrentHashMap<String, SseEmitter>` 维护用户 ID 与 HTTP 长连接的映射。
- **生命周期钩子**: 注册 `onCompletion`, `onTimeout`, `onError` 回调，确保连接断开时能准确清理内存中的句柄并更新在线人数 (`AtomicInteger`)。
- **消息封装**: 定义 `SSEMsgType` 枚举（包含 `MESSAGE`, `ADD`, `FINISH`, `DONE`），标准化前端处理增量文本与控制信令。

### 2.3 安全与权限 (`JwtUtil` & `AuthHelper`)
- **JWT 实现**: 基于 HMAC256 算法生成 Stateless Token。存储 `uid` (用户ID) 和 `subject` (用户名)。
- **多源提取策略**: `AuthHelper` 支持从 `Authorization` Header (Bearer)、自定义 Header (`headerUserToken`) 或 Query Parameter 中解析 Token。
- **异常处理**: 严格校验 Token 有效期与签名，防止未授权访问。

### 2.4 提示词管理 (`PromptLoader`)
- **动态加载**: 从类路径下的 `my_doctor` 文件读取系统级提示词。
- **解析协议**:
    - 使用自定义标记 `SYSTEM """ ... """` 提取 AI 的系统设定（角色扮演）。
    - 使用 `FROM` 标记识别模型版本或来源信息。
- **单例缓存**: 采用双重检查锁（隐含在 `synchronized` 方法中）确保提示词仅加载一次，提升性能。

## 3. 关键设计细节

### 关于 SSE 的集群方案建议
当前 `SSEServer` 基于 JVM 内存管理连接，适合单机环境。在集群部署时：
1. **不可序列化**: `SseEmitter` 无法存入 Redis。
2. **方案**: 需配合 **Redis Pub/Sub**。当节点 A 需要向节点 B 上的客户端发消息时，通过 Redis 发布消息，所有节点订阅后判断目标连接是否在本机，在本机则执行推送。

### 消息流程图
1. **Request**: 用户通过 API 发送消息。
2. **Auth**: `AuthHelper` 校验身份，提取 `userId`。
3. **SSE Connect**: 前端监听 SSE 端口，后端 `SSEServer` 保持连接。
4. **AI Processing**: `DashscopeChatClient` 获取 `Flux` 流。
5. **Pushing**: 后端逐个 Token 调用 `sseEmitter.send()`。
6. **End**: 发送 `SSEMsgType.DONE` 信令，客户端关闭动画。

## 4. 开发配置项
| 配置项 | 说明 |
| :--- | :--- |
| `dashscope.api-key` | 阿里云灵积平台 API 密钥 |
| `dashscope.model` | 使用的模型名称 (如 deepseek-v3) |
| `auth.jwt.secret` | JWT 签名密钥 |
| `dashscope.mock.enabled` | 是否开启测试模拟模式 |

---

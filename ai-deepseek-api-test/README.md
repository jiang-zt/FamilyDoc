# AI DeepSeek API Test

这是一个独立接口测试项目，用来测试旁边的 `deepseek-doctor` 服务。

第一阶段目标不是马上做复杂平台，而是先沉淀一套稳定的接口测试骨架：

- 用 REST Assured 编写接口自动化测试
- 用 JUnit 5 管理测试执行
- 用手写 OpenAPI 合约描述被测接口
- 为后续 Schemathesis、AI 失败分析、CI 报告预留结构

## 项目结构

```text
ai-deepseek-api-test
├── contracts
│   └── deepseek-doctor.openapi.yaml
├── src/test/java/com/itzixi/apitest
│   ├── AuthApiTest.java
│   ├── ChatApiTest.java
│   ├── OpenApiContractFileTest.java
│   ├── SseApiTest.java
│   └── support
└── pom.xml
```

## 先跑测试工程本身

这个命令只校验测试工程能不能编译、OpenAPI 合约文件是否存在，不会请求业务服务：

```bash
mvn test
```

## 启动被测服务

先启动 `deepseek-doctor`，默认测试地址是：

```text
http://localhost:8080
```

如果你的服务端口不是 8080，运行测试时用 `-Dapi.baseUrl` 覆盖。

## 执行真实接口测试

业务服务启动后，在本项目目录执行：

```bash
mvn test -Dapi.tests.enabled=true -Dapi.baseUrl=http://localhost:8080
```

当前覆盖的接口场景：

- 注册成功
- 登录成功
- 获取当前登录用户
- 空用户名/密码注册返回 400
- 重复注册返回 409
- 未登录访问受保护接口返回 401
- 普通用户访问管理员接口返回 403
- 聊天接口未登录返回 401
- 已登录但消息为空返回 400
- SSE 在线人数接口返回数字
- SSE 连接未登录返回 401

## 聊天成功用例

`/chat` 成功用例会调用模型层，所以默认关闭。

如果 `deepseek-doctor` 已经开启 DashScope mock，或你已经配置好真实模型 Key，可以打开：

```bash
mvn -pl ai-deepseek-api-test -am test \
  -Dapi.tests.enabled=true \
  -Dapi.chat.enabled=true \
  -Dapi.baseUrl=http://localhost:8080
```

## 医疗问答 Eval 评测

`medical-answer-scoring/src/main/resources/scoring/medical-eval-cases.yml` 维护了一组医疗问答评测用例。每条用例包含：

- `question`：待评测问题
- `rule_id`：期望命中的共享评分规则 ID

评分规则与评测用例都由公共模块 `medical-answer-scoring` 维护。线上 `accuracy_score`、评分器单测和离线 Eval 使用同一套规则与用例入口，避免重复维护。

Eval 会真实调用 `/chat`，用于评估模型回答质量，所以默认关闭。确认 `deepseek-doctor` 已配置真实模型 Key 后执行：

```bash
mvn -pl ai-deepseek-api-test -am test \
  -Dapi.tests.enabled=true \
  -Dapi.eval.enabled=true \
  -Dapi.baseUrl=http://localhost:8080
```

说明：

- Mock 模式适合回归接口链路，不适合评测真实回答质量。
- Eval 复用服务端同一套轻量规则评分，重点覆盖医疗安全底线；后续可继续接入语义相似度或 LLM Judge。

## 后续升级路线

下一步可以继续做三件事：

1. 给 `deepseek-doctor` 补 Swagger/OpenAPI 自动生成，替代手写合约。
2. 接入 Schemathesis，根据 OpenAPI 自动做接口 Fuzz 测试。
3. 接入 AI，把失败请求、响应、合约片段、日志片段交给模型做失败归因分析。

简历可以描述为：

```text
设计并实现独立接口测试工程，基于 JUnit5 + REST Assured 对 AI 医生服务进行认证、权限、聊天、SSE 等接口自动化测试；维护 OpenAPI 合约文件，为后续接口 Fuzz、CI 回归和 AI 失败归因分析提供基础。
```

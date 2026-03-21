# DeepSeek SpringAI Family Doctor - 大厂面试技术问答

**面试项目：** 家庭医生 AI 对话系统  
**技术栈：** Spring Boot 3.x + Spring AI + Ollama + MyBatis-Plus + MySQL + SSE  

---

## 📚 目录

1. [Spring Boot 核心](#1-spring-boot-核心)
2. [Spring AI 与 LLM 集成](#2-spring-ai-与-llm-集成)
3. [SSE 流式输出](#3-sse-流式输出)
4. [MyBatis-Plus 与数据库](#4-mybatis-plus-与数据库)
5. [并发编程与线程安全](#5-并发编程与线程安全)
6. [系统设计与架构](#6-系统设计与架构)
7. [性能优化](#7-性能优化)
8. [生产问题排查](#8-生产问题排查)
9. [扩展场景题](#9-扩展场景题)

---

## 1. Spring Boot 核心

### Q1: Spring Boot 自动装配原理是什么？

**答案：**

Spring Boot 自动装配的核心是 `@SpringBootApplication` 注解，它包含三个关键注解：

1. **`@SpringBootConfiguration`** - 标识这是一个配置类
2. **`@ComponentScan`** - 扫描当前包及子包下的 Bean
3. **`@EnableAutoConfiguration`** - 开启自动装配

**自动装配流程：**

```
启动 → 读取 spring.factories (或 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports) 
     → 加载所有 AutoConfiguration 类 
     → 根据 @Conditional 注解判断是否生效 
     → 注册 Bean 到 IOC 容器
```

**关键注解：**
- `@ConditionalOnClass` - 当类路径存在某类时生效
- `@ConditionalOnMissingBean` - 当容器中没有某 Bean 时生效
- `@ConditionalOnProperty` - 当配置文件有某属性时生效

**本项目中的体现：**
- `spring-boot-starter-web` 自动配置了 Tomcat、DispatcherServlet
- `spring-boot-starter-data-jpa` 自动配置了 DataSource、JPA
- `mybatis-plus-boot-starter` 自动配置了 SqlSessionFactory、Mapper 扫描

**面试加分点：**
> Spring Boot 2.7+ 使用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 替代了 `spring.factories`，性能更好。

---

### Q2: Spring Boot 启动流程是怎样的？

**答案：**

```java
SpringApplication.run(Application.class, args);
```

**启动五步：**

1. **初始化 SpringApplication 对象**
   - 推断应用类型（Servlet/Reactive/None）
   - 加载 ApplicationContextInitializer
   - 加载 ApplicationListener

2. **准备环境**
   - 创建 ConfigurableEnvironment
   - 加载配置文件（application.yml、application-dev.yml 等）
   - 绑定环境变量

3. **创建容器**
   - 根据应用类型创建 ApplicationContext（AnnotationConfigServletWebServerApplicationContext）

4. **刷新容器**
   - `refresh()` 方法核心流程：
     - 扫描 Bean 定义
     - 注册 BeanDefinition
     - 实例化单例 Bean
     - 调用 BeanPostProcessor
     - 调用 ApplicationRunner、CommandLineRunner

5. **运行完成**
   - 调用 ApplicationRunners
   - 输出启动日志
   - 返回 ApplicationContext

**本项目启动类：**
```java
@SpringBootApplication
@MapperScan("com.itzixi.mapper")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

**`@MapperScan` 作用：**
- 指定 MyBatis Mapper 接口所在包
- 自动扫描并注册 Mapper 代理对象到 Spring 容器

---

### Q3: Spring Bean 的生命周期？

**答案：**

**完整生命周期（12 步）：**

```
1. 实例化 Bean (Instantiation)
   ↓
2. 属性赋值 (Populate Bean)
   ↓
3. Aware 接口回调 (BeanNameAware、BeanFactoryAware、ApplicationContextAware)
   ↓
4. BeanPostProcessor.before()
   ↓
5. InitializingBean.afterPropertiesSet() / @PostConstruct
   ↓
6. 自定义 init-method
   ↓
7. Bean 就绪，可使用
   ↓
8. 业务调用
   ↓
9. DisposableBean.destroy() / @PreDestroy
   ↓
10. 自定义 destroy-method
   ↓
11. BeanPostProcessor.after()
   ↓
12. Bean 销毁
```

**本项目中的体现：**
- `@Service`、`@RestController` 默认单例，容器启动时创建
- `@Resource` 依赖注入在属性赋值阶段完成
- `SSEServer` 的静态初始化在类加载时完成

**面试加分点：**
> BeanPostProcessor 是 Spring 的核心扩展点，AOP、@Async、@Transactional 都通过它实现。

---

### Q4: Spring 中的设计模式有哪些？

**答案：**

| 设计模式 | Spring 中的应用 | 本项目体现 |
|----------|----------------|------------|
| **单例模式** | Bean 默认单例 | `@Service`、`@RestController` |
| **工厂模式** | BeanFactory、ApplicationContext | Spring 容器创建 Bean |
| **代理模式** | AOP、@Transactional | ServiceLogAspect |
| **模板方法** | JdbcTemplate、RestTemplate | - |
| **观察者模式** | ApplicationEvent、ApplicationListener | - |
| **适配器模式** | HandlerAdapter | Spring MVC 处理请求 |
| **策略模式** | Resource、Strategy 接口 | - |
| **依赖注入** | IOC 容器 | `@Resource`、`@Autowired` |

**本项目中的代理模式：**
```java
// ServiceLogAspect 使用 AOP 代理
@Aspect
public class ServiceLogAspect {
    @Around("execution(* com.itzixi.service..*.*(..))")
    public Object log(ProceedingJoinPoint pjp) { ... }
}
```

---

## 2. Spring AI 与 LLM 集成

### Q5: Spring AI 是什么？如何集成 Ollama？

**答案：**

**Spring AI** 是 Spring 官方推出的 AI 框架，提供统一的 API 接入各种 LLM 提供商（OpenAI、Anthropic、Ollama 等）。

**核心组件：**
- `ChatClient` - 对话客户端
- `ChatModel` - 底层模型接口
- `Prompt` - 提示词封装
- `ChatResponse` - 响应封装

**Ollama 集成步骤：**

1. **添加依赖**
```xml
<dependency>
    <groupId>io.springboot.ai</groupId>
    <artifactId>spring-ai-ollama</artifactId>
    <version>1.0.3</version>
</dependency>
<dependency>
    <groupId>io.springboot.ai</groupId>
    <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
    <version>1.0.3</version>
</dependency>
```

2. **配置文件**
```yaml
spring:
  ai:
    ollama:
      base-url: http://127.0.0.1:11434
      chat:
        model: deepseek-coder:latest
```

3. **注入使用**
```java
@Resource
private OllamaChatClient ollamaChatClient;

// 同步调用
String response = ollamaChatClient.call("你好");

// 流式调用
Flux<ChatResponse> stream = ollamaChatClient.stream(new Prompt(new UserMessage("你好")));
```

**Ollama 是什么：**
- 本地运行 LLM 的工具
- 支持 DeepSeek、Llama、Mistral 等开源模型
- API 兼容 OpenAI 格式
- 无需 API 密钥，完全本地运行

**面试加分点：**
> Spring AI 的 `ChatMemory` 支持上下文记忆，通过 `MessageChatMemoryAdvisor` 实现多轮对话。生产环境建议使用 Redis 存储对话历史。

---

### Q6: 同步调用 vs 流式调用有什么区别？

**答案：**

| 对比项 | 同步调用 | 流式调用 |
|--------|----------|----------|
| **方法** | `ollamaChatClient.call()` | `ollamaChatClient.stream()` |
| **返回类型** | `String` | `Flux<ChatResponse>` |
| **响应方式** | 等待完整响应 | 逐 token 推送 |
| **用户体验** | 卡顿，等待时间长 | 流畅，实时显示 |
| **适用场景** | 简单问答、后台任务 | 对话界面、实时交互 |
| **资源占用** | 连接阻塞直到完成 | 连接保持，逐步释放 |

**本项目中的三种流式实现：**

```java
// stream1 - 直接返回 Flux
@GetMapping("/ai/stream1")
public Flux<ChatResponse> aiOllamaStream1(@RequestParam String msg) {
    Prompt prompt = new Prompt(new UserMessage(msg));
    return ollamaChatClient.stream(prompt);
}

// stream2 - 手动收集为 List
@GetMapping("/ai/stream2")
public List<String> aiOllamaStream2(@RequestParam String msg) {
    Flux<ChatResponse> streamResponse = ollamaChatClient.stream(prompt);
    return streamResponse.toStream()
        .map(r -> r.getResult().getOutput().getContent())
        .collect(Collectors.toList());
}

// v3 - SSE 推送（生产版本）
@PostMapping("/ai/v3/doctor/stream")
public void aiOllamaV3DoctorStream(@RequestBody ChatEntity chatEntity) {
    Flux<ChatResponse> streamResponse = ollamaChatClient.stream(prompt);
    streamResponse.toStream().forEach(content -> {
        SSEServer.sendMessage(userName, content, SSEMsgType.ADD);
    });
    SSEServer.sendMessage(userName, "GG", SSEMsgType.FINISH);
}
```

**面试加分点：**
> 流式输出本质是 Server-Sent Events (SSE)，基于 HTTP 长连接，单向推送。WebSocket 是双向的，更适合实时聊天应用。

---

### Q7: 如何实现 AI 对话的上下文记忆？

**答案：**

**方案一：Spring AI ChatMemory（内存版）**
```java
@Resource
private ChatClient chatClient;
private InMemoryChatMemory chatMemory = new InMemoryChatMemory();

@PostMapping("/ai/v4/doctor/stream")
public Flux<String> chat(@RequestBody ChatEntity chatEntity) {
    Prompt prompt = new Prompt(new UserMessage(chatEntity.getMessage()));
    return chatClient.prompt(prompt)
        .advisors(new MessageChatMemoryAdvisor(
            chatMemory, 
            chatEntity.getCurrentUserName(), 
            250  // 最大 token 数
        ))
        .stream().content();
}
```

**问题：** `InMemoryChatMemory` 重启后数据丢失，不适合生产。

**方案二：数据库持久化（本项目方案）**
```java
// 保存用户消息
chatRecordService.saveChatRecord(userName, message, ChatTypeEnum.USER);

// 保存 AI 回复
chatRecordService.saveChatRecord(userName, aiResponse, ChatTypeEnum.BOT);

// 查询历史记录
List<ChatRecord> history = chatRecordService.getChatRecordList(userName);
```

**方案三：Redis + 向量数据库（生产方案）**
```java
// Redis 存储最近 N 条对话
redis.opsForList().range("chat:" + userId, 0, 9);

// 向量数据库存储语义记忆
chromaClient.queryCollection("medical_knowledge", queryVector, topK=5);
```

**面试加分点：**
> 大厂生产环境通常使用：Redis（短期会话）+ MySQL/ES（长期存储）+ 向量数据库（语义检索）。上下文窗口有限，需要实现滑动窗口或摘要压缩。

---

## 3. SSE 流式输出

### Q8: SSE (Server-Sent Events) 原理是什么？

**答案：**

**SSE 是单向 HTTP 长连接**，服务器主动推送数据给客户端。

**核心 API：**
```java
SseEmitter sseEmitter = new SseEmitter(0L);  // 0 = 永不过期
sseEmitter.send(SseEmitter.event()
    .id(userId)
    .name("message")
    .data(content)
);
```

**工作流程：**
```
1. 客户端发起 GET 请求，设置 Accept: text/event-stream
2. 服务器创建 SseEmitter，保持连接
3. 服务器调用 send() 推送数据
4. 客户端通过 EventSource.onmessage 接收
5. 连接关闭时调用 complete()
```

**本项目 SSE 实现：**
```java
// 建立连接
public static SseEmitter connect(String userId) {
    SseEmitter sseEmitter = new SseEmitter(0L);
    sseEmitter.onCompletion(completionCallback(userId));
    sseEmitter.onError(errorCallback(userId));
    sseEmitter.onTimeout(timeoutCallback(userId));
    sseClients.put(userId, sseEmitter);
    onlineCounts.getAndIncrement();
    return sseEmitter;
}

// 推送消息
public static void sendMessage(String userId, String message, SSEMsgType msgType) {
    SseEmitter sseEmitter = sseClients.get(userId);
    sseEmitter.send(SseEmitter.event()
        .id(userId)
        .name(msgType.type)
        .data(message)
    );
}
```

**回调处理：**
```java
// 连接完成
private static Runnable completionCallback(String userId) {
    return () -> {
        log.info("SSE 连接完成，用户 ID: {}", userId);
        removeConnection(userId);
    };
}

// 连接超时
private static Runnable timeoutCallback(String userId) {
    return () -> {
        log.info("SSE 连接超时，用户 ID: {}", userId);
        removeConnection(userId);
    };
}

// 连接错误
private static Consumer<Throwable> errorCallback(String userId) {
    return throwable -> {
        log.info("SSE 连接错误，用户 ID: {}", userId);
        removeConnection(userId);
    };
}
```

---

### Q9: SSE vs WebSocket 有什么区别？

**答案：**

| 对比项 | SSE | WebSocket |
|--------|-----|-----------|
| **协议** | HTTP (text/event-stream) | ws:// / wss:// |
| **通信方向** | 单向（服务器→客户端） | 双向 |
| **浏览器支持** | 原生 EventSource API | 原生 WebSocket API |
| **复杂度** | 简单，基于 HTTP | 复杂，需要握手协议 |
| **适用场景** | 通知、日志、流式输出 | 实时聊天、游戏、协作 |
| **跨域** | 需要 CORS 配置 | 需要握手处理 |
| **负载均衡** | 需要 sticky session | 需要连接迁移 |

**本项目选择 SSE 的原因：**
- AI 流式输出是单向推送（服务器→客户端）
- 基于 HTTP，无需额外协议
- 实现简单，Spring 原生支持
- 适合医疗咨询场景（用户提问→AI 回复）

**面试加分点：**
> 大厂高并发场景下，SSE 和 WebSocket 都需要考虑：连接数限制、心跳检测、断线重连、负载均衡 sticky session。

---

### Q10: SSE 连接如何管理？线程安全问题？

**答案：**

**本项目连接管理：**
```java
// 使用 ConcurrentHashMap 存储用户连接
private static Map<String, SseEmitter> sseClients = new ConcurrentHashMap<>();

// 使用 AtomicInteger 统计在线人数
private static AtomicInteger onlineCounts = new AtomicInteger(0);

// 添加连接
sseClients.put(userId, sseEmitter);
onlineCounts.getAndIncrement();

// 移除连接
sseClients.remove(userId);
onlineCounts.getAndDecrement();
```

**线程安全分析：**

1. **ConcurrentHashMap** - 线程安全的 Map
   - 分段锁（JDK7）或 CAS + synchronized（JDK8）
   - 读操作无锁，写操作细粒度锁
   - 适合高并发读多写少场景

2. **AtomicInteger** - 原子整数
   - 基于 CAS (Compare-And-Swap)
   - 无锁实现，性能优于 synchronized
   - `getAndIncrement()`、`getAndDecrement()` 是原子操作

**潜在问题：**
```java
// 问题：检查 + 操作不是原子操作
if (sseClients.containsKey(userId)) {
    SseEmitter sseEmitter = sseClients.get(userId);
    sseEmitter.send(...);  // 可能此时连接已被移除
}

// 解决：使用 computeIfPresent 或加锁
sseClients.computeIfPresent(userId, (k, emitter) -> {
    emitter.send(...);
    return emitter;
});
```

**面试加分点：**
> 生产环境建议使用 `ConcurrentHashMap.compute()` 方法保证原子性，或使用 `ReentrantLock` 显式锁。

---

### Q11: SSE 超时问题如何处理？

**答案：**

**SseEmitter 超时配置：**
```java
// 0 = 永不过期（默认 30 秒）
SseEmitter sseEmitter = new SseEmitter(0L);
```

**超时回调：**
```java
sseEmitter.onTimeout(() -> {
    log.info("SSE 连接超时，用户 ID: {}", userId);
    removeConnection(userId);
});
```

**生产环境建议：**
1. 设置合理超时时间（如 5 分钟）
2. 实现心跳机制（定期发送空消息）
3. 客户端实现重连逻辑
4. 服务端清理僵尸连接

**心跳实现：**
```java
// 定时任务发送心跳
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
scheduler.scheduleAtFixedRate(() -> {
    sseClients.forEach((userId, emitter) -> {
        try {
            emitter.send(SseEmitter.event().data("ping"));
        } catch (IOException e) {
            removeConnection(userId);
        }
    });
}, 0, 30, TimeUnit.SECONDS);
```

---

## 4. MyBatis-Plus 与数据库

### Q12: MyBatis-Plus 相比 MyBatis 有什么优势？

**答案：**

| 对比项 | MyBatis | MyBatis-Plus |
|--------|---------|--------------|
| **CRUD** | 手写 SQL | 内置通用 Mapper |
| **分页** | 手动实现 | 内置 PaginationInterceptor |
| **条件构造** | 手写 WHERE | QueryWrapper、LambdaQueryWrapper |
| **代码生成** | 手动 | AutoGenerator |
| **插件体系** | 有限 | 丰富（分页、乐观锁、枚举等） |

**本项目使用：**
```java
// Mapper 接口（无需实现）
@Mapper
public interface ChatRecordMapper extends BaseMapper<ChatRecord> {
    // 继承 BaseMapper，自动获得 CRUD 方法
}

// Service 使用
chatRecordMapper.insert(chatRecord);  // 插入
chatRecordMapper.selectList(queryWrapper);  // 查询
chatRecordMapper.updateById(chatRecord);  // 更新
chatRecordMapper.deleteById(id);  // 删除
```

**条件查询：**
```java
// QueryWrapper
QueryWrapper<ChatRecord> wrapper = new QueryWrapper<>();
wrapper.eq("family_member", userName)
       .orderByDesc("chat_time");
List<ChatRecord> list = chatRecordMapper.selectList(wrapper);

// LambdaQueryWrapper（类型安全）
LambdaQueryWrapper<ChatRecord> wrapper = Wrappers.lambdaQuery();
wrapper.eq(ChatRecord::getFamilyMember, userName)
       .orderByDesc(ChatRecord::getChatTime);
```

**面试加分点：**
> MyBatis-Plus 3.5.0+ 解决了与 Spring 6 的兼容性问题，核心是 `MybatisSqlSessionFactoryBean` 的类加载隔离。

---

### Q13: MyBatis 的 #{} 和 ${} 有什么区别？

**答案：**

| 对比项 | #{} | ${} |
|--------|-----|-----|
| **处理方式** | 预编译（PreparedStatement） | 字符串替换 |
| **SQL 注入** | 防止 | 风险 |
| **性能** | 高（可复用执行计划） | 低 |
| **使用场景** | 参数值 | 动态表名、列名 |

**示例：**
```xml
<!-- 安全：预编译 -->
<select id="selectByUser" resultType="ChatRecord">
    SELECT * FROM chat_record WHERE family_member = #{familyMember}
</select>

<!-- 危险：字符串替换 -->
<select id="selectByTable" resultType="ChatRecord">
    SELECT * FROM ${tableName} WHERE id = #{id}
</select>
```

**本项目中的使用：**
```xml
<!-- mappers/ChatRecordMapper.xml -->
<select id="getChatRecordList" resultType="com.itzixi.bean.ChatRecord">
    SELECT * FROM chat_record 
    WHERE family_member = #{userName}
    ORDER BY chat_time DESC
</select>
```

**面试加分点：**
> 动态 SQL 使用 `<if>`、`<choose>`、`<foreach>` 标签，避免 SQL 注入的同时保持灵活性。

---

### Q14: 数据库 ID 生成策略有哪些？本项目用的是什么？

**答案：**

**常见 ID 生成策略：**

| 策略 | 说明 | 优点 | 缺点 |
|------|------|------|------|
| **自增 ID** | AUTO_INCREMENT | 简单 | 分布式不安全 |
| **UUID** | UUID.randomUUID() | 全局唯一 | 无序，索引效率低 |
| **雪花算法** | Snowflake | 有序，全局唯一 | 依赖时钟 |
| **号段模式** | 批量获取 ID | 性能高 | 实现复杂 |
| **Redis 自增** | INCR | 简单 | 依赖 Redis |

**本项目配置：**
```yaml
mybatis-plus:
  global-config:
    db-config:
      id-type: assign_id  # 雪花算法
```

**雪花算法（Snowflake）：**
```
结构：64 bit
├─ 1 bit: 符号位（0）
├─ 41 bit: 时间戳（毫秒级，约 69 年）
├─ 10 bit: 机器 ID（5 位数据中心 + 5 位机器 ID）
└─ 12 bit: 序列号（同一毫秒内的递增序号）
```

**优点：**
- 全局唯一
- 趋势递增（索引友好）
- 不依赖数据库
- 高性能

**面试加分点：**
> 分布式场景下，雪花算法的时钟回拨问题需要处理。大厂通常使用美团 Leaf、百度 UidGenerator 等成熟方案。

---

### Q15: 数据库索引优化原则？

**答案：**

**索引创建原则：**

1. **高频查询字段** - WHERE、JOIN、ORDER BY 字段
2. **区分度高** - 基数大的字段（如 ID、时间）
3. **复合索引** - 最左前缀匹配原则
4. **避免过度索引** - 写操作多的表谨慎

**本项目索引建议：**
```sql
-- 查询条件
CREATE INDEX idx_family_member ON chat_record(family_member);

-- 时间排序
CREATE INDEX idx_chat_time ON chat_record(chat_time DESC);

-- 复合索引（推荐）
CREATE INDEX idx_member_time ON chat_record(family_member, chat_time DESC);
```

**最左前缀原则：**
```sql
-- 复合索引 (a, b, c)
WHERE a = 1              -- 命中
WHERE a = 1 AND b = 2    -- 命中
WHERE b = 2              -- 不命中
WHERE c = 3              -- 不命中
```

**面试加分点：**
> 覆盖索引（Covering Index）可以避免回表，`SELECT id FROM table WHERE xxx` 如果 id 是主键，直接走索引。

---

## 5. 并发编程与线程安全

### Q16: ConcurrentHashMap 原理是什么？

**答案：**

**JDK8 实现：**

```
结构：数组 + 链表 + 红黑树
├─ Node[] table: 哈希桶数组
├─ 链表：hash 冲突时链式存储
└─ 红黑树：链表长度 > 8 时树化
```

**线程安全机制：**

1. **CAS + synchronized**
   - 头节点插入用 CAS
   - 链表/树操作用 synchronized 锁头节点

2. **细粒度锁**
   - 锁粒度是桶（bin），不是整个 Map
   - 并发度高

3. **volatile 变量**
   - `volatile Node<K,V>[] table`
   - 保证可见性

**核心方法：**
```java
// put 操作
public V put(K key, V value) {
    return putVal(key, value, false);
}

private final V putVal(K key, V value, boolean onlyIfAbsent) {
    // 1. 计算 hash
    int hash = spread(key.hashCode());
    // 2. 尝试 CAS 插入（无锁）
    if (tab == null || ...)
        initTable();
    // 3. 桶为空，CAS 插入
    if ((f = tabAt(tab, i)) == null) {
        if (casTabAt(tab, i, null, new Node<>(hash, key, value)))
            break;
    }
    // 4. 桶有数据，synchronized 锁头节点
    else {
        synchronized (f) {
            // 链表/树插入
        }
    }
    // 5. 计数
    addCount(1L, 0);
}
```

**本项目使用：**
```java
private static Map<String, SseEmitter> sseClients = new ConcurrentHashMap<>();
// 线程安全的 put/get/remove
sseClients.put(userId, sseEmitter);
SseEmitter emitter = sseClients.get(userId);
sseClients.remove(userId);
```

**面试加分点：**
> JDK7 使用分段锁（Segment），JDK8 改为 CAS + synchronized，性能提升 3-5 倍。

---

### Q17: AtomicInteger 原理是什么？

**答案：**

**AtomicInteger 基于 CAS（Compare-And-Swap）：**

```java
// 内部字段
private volatile int value;
private static final Unsafe U = Unsafe.getUnsafe();
private static final long VALUE = U.objectFieldOffset(AtomicInteger.class, "value");

// 原子自增
public final int getAndIncrement() {
    return U.getAndAddInt(this, VALUE, 1);
}

// CAS 实现（伪代码）
boolean compareAndSwap(int expected, int update) {
    if (value == expected) {
        value = update;
        return true;
    }
    return false;
}
```

**CPU 级指令：**
```
xaddl %eax, (%rdi)  // x86 原子加法指令
lock; cmpxchg       // 锁定总线，比较交换
```

**本项目使用：**
```java
private static AtomicInteger onlineCounts = new AtomicInteger(0);

// 原子操作，线程安全
onlineCounts.getAndIncrement();  // +1
onlineCounts.getAndDecrement();  // -1
int count = onlineCounts.get();   // 获取当前值
```

**vs synchronized：**
| 对比项 | AtomicInteger | synchronized |
|--------|---------------|--------------|
| **实现** | CAS（乐观锁） | 监视器锁（悲观锁） |
| **性能** | 高（无锁） | 低（锁竞争） |
| **适用** | 简单原子操作 | 复杂同步逻辑 |

**面试加分点：**
> CAS 有 ABA 问题，`AtomicStampedReference` 通过版本号解决。高并发场景下，`LongAdder` 比 `AtomicLong` 性能更好（分段累加）。

---

### Q18: 什么是 volatile？有什么用？

**答案：**

**volatile 两大特性：**

1. **可见性**
   - 一个线程修改 volatile 变量，其他线程立即可见
   - 通过内存屏障（Memory Barrier）实现
   - 写操作刷新到主内存，读操作从主内存读取

2. **有序性**
   - 禁止指令重排序
   - 通过插入内存屏障实现

**底层实现：**
```
写屏障：StoreStore + StoreLoad
读屏障：LoadLoad + LoadStore
```

**示例：**
```java
// 问题：非 volatile 可能导致可见性问题
private boolean ready;  // 线程 A 修改，线程 B 可能看不到

// 解决：使用 volatile
private volatile boolean ready;
```

**本项目中的 volatile：**
```java
// ConcurrentHashMap 内部
private volatile Node<K,V>[] table;
// 保证数组引用的可见性
```

**volatile 不保证原子性：**
```java
// 问题：volatile 不保证复合操作原子性
volatile int count = 0;
count++;  // 不是原子操作（读 - 改 - 写）

// 解决：使用 AtomicInteger
AtomicInteger count = new AtomicInteger(0);
count.getAndIncrement();  // 原子操作
```

**面试加分点：**
> JMM（Java 内存模型）通过 happens-before 规则定义内存可见性。volatile 写 happens-before 后续 volatile 读。

---

### Q19: 线程池核心参数有哪些？如何配置？

**答案：**

**ThreadPoolExecutor 7 个参数：**

```java
new ThreadPoolExecutor(
    int corePoolSize,      // 核心线程数
    int maximumPoolSize,   // 最大线程数
    long keepAliveTime,    // 非核心线程存活时间
    TimeUnit unit,         // 时间单位
    BlockingQueue<Runnable> workQueue,  // 工作队列
    ThreadFactory threadFactory,        // 线程工厂
    RejectedExecutionHandler handler    // 拒绝策略
)
```

**工作流程：**
```
1. 提交任务 → 核心线程未满 → 创建核心线程执行
2. 核心线程已满 → 加入工作队列
3. 队列已满 → 创建非核心线程（直到最大线程数）
4. 达到最大线程数 → 执行拒绝策略
```

**拒绝策略：**
| 策略 | 说明 |
|------|------|
| **AbortPolicy** | 抛出 RejectedExecutionException（默认） |
| **CallerRunsPolicy** | 调用者线程执行 |
| **DiscardPolicy** | 丢弃任务 |
| **DiscardOldestPolicy** | 丢弃最老任务 |

**本项目中的使用：**
```java
// SSE 心跳任务
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
scheduler.scheduleAtFixedRate(...);

// 生产环境建议自定义线程池
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    5,                      // 核心线程
    20,                     // 最大线程
    60L, TimeUnit.SECONDS,  // 存活时间
    new LinkedBlockingQueue<>(1000),  // 队列
    new ThreadFactoryBuilder().setNameFormat("sse-%d").build(),
    new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略
);
```

**面试加分点：**
> `Executors.newFixedThreadPool()` 和 `newCachedThreadPool()` 的队列是无界的，可能导致 OOM。生产环境使用 `ThreadPoolExecutor` 构造函数显式配置。

---

## 6. 系统设计与架构

### Q20: 如何设计一个高可用的 AI 对话系统？

**答案：**

**架构分层：**

```
┌─────────────────────────────────────────┐
│           负载均衡层 (Nginx/SLB)         │
│   - 连接数限制                          │
│   - 健康检查                            │
│   - 限流熔断                            │
├─────────────────────────────────────────┤
│           应用层 (Spring Boot 集群)      │
│   - 多实例部署                          │
│   - 会话粘性 (sticky session)           │
│   - 本地缓存 + 分布式缓存               │
├─────────────────────────────────────────┤
│           缓存层 (Redis)                 │
│   - 对话会话存储                        │
│   - 热点数据缓存                        │
│   - 分布式锁                            │
├─────────────────────────────────────────┤
│           数据层 (MySQL 集群)            │
│   - 主从复制                            │
│   - 读写分离                            │
│   - 分库分表                            │
├─────────────────────────────────────────┤
│           AI 层 (Ollama 集群)            │
│   - 模型负载均衡                        │
│   - GPU 资源调度                        │
│   - 降级策略                            │
└─────────────────────────────────────────┘
```

**高可用措施：**

1. **负载均衡**
   - Nginx 反向代理
   - 健康检查自动摘除故障节点
   - 会话粘性保证 SSE 连接不中断

2. **限流熔断**
   ```java
   // Sentinel 限流
   @SentinelResource(value = "aiChat", blockHandler = "handleBlock")
   public String chat(String msg) { ... }
   ```

3. **降级策略**
   - AI 服务不可用时返回友好提示
   - 缓存热门问答
   - 异步队列缓冲请求

4. **监控告警**
   - Prometheus + Grafana
   - 响应时间、错误率、连接数监控
   - 自动告警

**面试加分点：**
> 大厂生产环境使用 Service Mesh（如 Istio）管理流量，AI 服务通常部署在 GPU 集群，通过 Kubernetes 自动伸缩。

---

### Q21: SSE 连接在集群环境下如何处理？

**答案：**

**问题：** SSE 连接绑定到单个 JVM，集群环境下用户可能连接到不同节点。

**解决方案：**

**方案一：会话粘性（Sticky Session）**
```nginx
# Nginx 配置
upstream backend {
    ip_hash;  # 按 IP 哈希，同一 IP 总是到同一节点
    server node1:8080;
    server node2:8080;
}
```

**优点：** 简单
**缺点：** 负载不均，节点故障后连接中断

**方案二：Redis 发布/订阅**
```java
// 节点 A 保存连接
sseClients.put(userId, emitter);

// 节点 B 需要推送消息
redisTemplate.convertAndSend("sse:" + userId, message);

// 所有节点订阅
redisMessageListenerContainer.addMessageListener(
    (channel, msg) -> {
        String userId = extractUserId(channel);
        SSEServer.sendMessage(userId, msg);
    },
    new PatternTopic("sse:*")
);
```

**优点：** 高可用，节点故障自动切换
**缺点：** 实现复杂，需要 Redis

**方案三：WebSocket + 消息队列**
```java
// 使用 RabbitMQ/Kafka 广播消息
rabbitTemplate.convertAndSend("sse.exchange", userId, message);
```

**面试加分点：**
> 大厂通常使用方案二（Redis Pub/Sub）或方案三（消息队列），配合 Kubernetes 的亲和性调度保证连接稳定性。

---

### Q22: 如何设计数据库表结构支持千万级数据？

**答案：**

**分库分表策略：**

**1. 水平分表（按用户）**
```sql
-- 按 family_member 哈希分表
chat_record_00, chat_record_01, ..., chat_record_99

-- 路由规则
tableIndex = Math.abs(userId.hashCode()) % 100;
```

**2. 垂直分表（按时间）**
```sql
-- 按月分表
chat_record_202501, chat_record_202502, ...
```

**3. 读写分离**
```
主库：写操作（insert、update）
从库：读操作（select）
```

**4. 冷热分离**
```
热数据：最近 3 个月（SSD 存储）
冷数据：历史数据（HDD 存储/归档）
```

**本项目优化建议：**
```sql
-- 添加索引
CREATE INDEX idx_member_time ON chat_record(family_member, chat_time DESC);

-- 分区表（按时间）
CREATE TABLE chat_record (
    id VARCHAR(255),
    content TEXT,
    chat_time DATETIME,
    family_member VARCHAR(100)
) PARTITION BY RANGE (YEAR(chat_time)) (
    PARTITION p2024 VALUES LESS THAN (2025),
    PARTITION p2025 VALUES LESS THAN (2026),
    PARTITION p2026 VALUES LESS THAN (2027)
);
```

**面试加分点：**
> 大厂使用 ShardingSphere 实现透明分库分表，ES 存储历史对话用于检索，MySQL 只存最近 N 个月数据。

---

## 7. 性能优化

### Q23: 如何优化 AI 对话的响应时间？

**答案：**

**优化策略：**

**1. 模型选择**
- 使用小模型（如 DeepSeek-Coder-1.3B vs 67B）
- 量化模型（INT8/INT4）减少显存占用
- 本地 GPU 加速

**2. 流式输出**
- 首 token 时间 < 100ms
- 用户感知更快（无需等待完整响应）

**3. 缓存热门问答**
```java
// Redis 缓存
String cacheKey = "faq:" + DigestUtils.md5Hex(question);
String cachedAnswer = redisTemplate.opsForValue().get(cacheKey);
if (cachedAnswer != null) {
    return cachedAnswer;
}
// 调用 AI
String answer = ollamaChatClient.call(question);
redisTemplate.opsForValue().set(cacheKey, answer, 7, TimeUnit.DAYS);
```

**4. 异步处理**
```java
// 聊天记录保存异步化
@Async
public void saveChatRecord(...) {
    // 不阻塞主流程
}
```

**5. 连接池优化**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

**面试加分点：**
> 大厂使用模型蒸馏（Distillation）+ 量化（Quantization）+ 批处理（Batching）优化推理性能，首 token 时间可压到 50ms 以内。

---

### Q24: 如何优化数据库查询性能？

**答案：**

**优化手段：**

**1. 索引优化**
```sql
-- 复合索引（最左前缀）
CREATE INDEX idx_member_time ON chat_record(family_member, chat_time DESC);

-- 覆盖索引（避免回表）
SELECT id, content, chat_time FROM chat_record 
WHERE family_member = ? ORDER BY chat_time DESC LIMIT 20;
```

**2. 分页优化**
```sql
-- 问题：深分页性能差
SELECT * FROM chat_record ORDER BY chat_time DESC LIMIT 10000, 20;

-- 优化：使用索引覆盖 + 子查询
SELECT * FROM chat_record 
WHERE chat_time <= (SELECT chat_time FROM chat_record ORDER BY chat_time DESC LIMIT 10000, 1)
ORDER BY chat_time DESC LIMIT 20;

-- 或：使用游标分页（推荐）
SELECT * FROM chat_record 
WHERE family_member = ? AND chat_time < ?
ORDER BY chat_time DESC LIMIT 20;
```

**3. 批量操作**
```java
// 批量插入（MyBatis-Plus）
chatRecordMapper.insertBatch(list);  // 一次 SQL 插入多条
```

**4. 慢查询日志**
```yaml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
    # 生产环境使用
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
    # 开启慢查询
    slow-query-limit: 1000  # ms
```

**面试加分点：**
> 大厂使用 EXPLAIN 分析执行计划，避免 filesort 和 temporary。千万级数据表使用覆盖索引 + 游标分页，QPS 可达 1000+。

---

### Q25: 如何减少内存占用？

**答案：**

**内存优化策略：**

**1. SSE 连接清理**
```java
// 及时清理失效连接
sseEmitter.onCompletion(() -> removeConnection(userId));
sseEmitter.onTimeout(() -> removeConnection(userId));
sseEmitter.onError(() -> removeConnection(userId));
```

**2. 对象池化**
```java
// 复用 SseEmitter（谨慎使用）
// 或使用对象池
CommonsObjectPool2 的 GenericObjectPool
```

**3. 流式处理**
```java
// 不要一次性加载所有数据
Flux<ChatResponse> stream = ollamaChatClient.stream(prompt);
stream.toStream().forEach(content -> {
    SSEServer.sendMessage(userId, content, SSEMsgType.ADD);
});
// 逐 token 处理，不占用大量内存
```

**4. 弱引用缓存**
```java
// 使用 WeakHashMap 缓存
Map<String, SoftReference<SseEmitter>> cache = new WeakHashMap<>();
```

**5. JVM 调优**
```bash
# 启动参数
-Xms512m -Xmx1g           # 堆内存
-XX:MaxMetaspaceSize=256m  # 元空间
-XX:+UseG1GC               # G1 垃圾回收器
-XX:MaxGCPauseMillis=200   # 最大 GC 停顿时间
```

**面试加分点：**
> 使用 `jmap -histo` 分析内存占用，`jstack` 分析线程状态。SSE 连接数过多时使用 `WeakReference` 或定期清理。

---

## 8. 生产问题排查

### Q26: 线上 CPU 100% 如何排查？

**答案：**

**排查步骤：**

```bash
# 1. 找到高 CPU 的进程
top -c

# 2. 找到高 CPU 的线程
top -H -p <pid>

# 3. 转换线程 ID 为十六进制
printf "%x\n" <tid>

# 4. 导出线程栈
jstack <pid> > thread_dump.txt

# 5. 搜索十六进制线程 ID
grep -A 20 <hex_tid> thread_dump.txt
```

**常见原因：**
1. **死循环** - 检查业务代码
2. **频繁 GC** - 内存不足，检查堆内存
3. **序列化/反序列化** - 大对象处理
4. **正则表达式** - 回溯灾难
5. **锁竞争** - synchronized 死锁

**本项目可能的问题：**
```java
// 问题：ConcurrentHashMap 遍历时的死循环（JDK7）
for (String key : sseClients.keySet()) { ... }

// 解决：使用 JDK8 或 Iterator
Iterator<Map.Entry<String, SseEmitter>> it = sseClients.entrySet().iterator();
while (it.hasNext()) { ... }
```

**面试加分点：**
> 使用 `async-profiler` 生成火焰图（Flame Graph），直观看到 CPU 热点。Arthas 的 `thread -n 3` 命令快速定位。

---

### Q27: 线上 OOM 如何排查？

**答案：**

**排查步骤：**

```bash
# 1. 查看堆内存
jmap -heap <pid>

# 2. 导出堆 dump
jmap -dump:format=b,file=heap.hprof <pid>

# 3. 分析 dump 文件
jhat heap.hprof  # 内置工具（不推荐）
MAT (Memory Analyzer Tool)  # 推荐
VisualVM  # 图形化

# 4. 查看 GC 日志
-XX:+PrintGCDetails -XX:+PrintGCDateStamps -Xloggc:gc.log
```

**常见原因：**
1. **内存泄漏** - 静态集合持续增长
2. **大对象** - 一次性加载大量数据
3. **缓存无限制** - 未设置 TTL 或大小限制
4. **ThreadLocal 未清理** - 线程池 + ThreadLocal

**本项目可能的问题：**
```java
// 问题：SSE 连接未清理，内存泄漏
private static Map<String, SseEmitter> sseClients = new ConcurrentHashMap<>();
// 用户断开后未 remove，Map 持续增长

// 解决：确保回调清理
sseEmitter.onCompletion(() -> removeConnection(userId));
```

**面试加分点：**
> 使用 `jmap -histo:live` 查看存活对象，MAT 的 Dominator Tree 找到内存泄漏根因。生产环境配置 `-XX:+HeapDumpOnOutOfMemoryError` 自动 dump。

---

### Q28: 线上接口响应慢如何排查？

**答案：**

**排查步骤：**

```bash
# 1. 查看应用监控（Prometheus/Grafana）
响应时间、QPS、错误率

# 2. 查看慢查询日志
mybatis-plus 慢查询
mysql slow_query_log

# 3. 查看线程状态
jstack <pid> | grep -A 20 "RUNNABLE\|BLOCKED"

# 4. 查看网络延迟
curl -w "@curl-format.txt" -o /dev/null -s URL

# 5. 使用 Arthas 追踪
trace com.itzixi.controller.OllamaController aiOllamaChat
```

**常见原因：**
1. **数据库慢查询** - 缺少索引、深分页
2. **AI 模型响应慢** - 模型太大、GPU 不足
3. **锁竞争** - synchronized 阻塞
4. **Full GC** - 停顿时间长
5. **网络延迟** - 跨机房调用

**本项目优化：**
```java
// 添加超时控制
@GetMapping("/ai/chat")
@Timeout(value = 5, unit = TimeUnit.SECONDS)  // 5 秒超时
public Object aiOllamaChat(@RequestParam String msg) {
    return ollamaChatClient.call(msg);
}
```

**面试加分点：**
> 使用 SkyWalking/Pinpoint 链路追踪，定位跨服务调用瓶颈。数据库使用 `EXPLAIN ANALYZE` 分析实际执行时间。

---

## 9. 扩展场景题

### Q29: 如果让你支持多轮对话（上下文记忆），如何设计？

**答案：**

**设计方案：**

**1. 数据库方案（本项目扩展）**
```java
// 查询最近 N 条历史
List<ChatRecord> history = chatRecordService.getChatRecordList(userName, 10);

// 构建上下文
StringBuilder context = new StringBuilder();
for (ChatRecord record : history) {
    context.append(record.getChatType()).append(": ").append(record.getContent()).append("\n");
}

// 添加到 prompt
Prompt prompt = new Prompt(
    new SystemMessage("你是家庭医生，以下是对话历史:\n" + context),
    new UserMessage(currentMessage)
);
```

**2. Redis 方案（高性能）**
```java
// 使用 List 存储对话
redis.opsForList().rightPush("chat:" + userId, userMessage);
redis.opsForList().rightPush("chat:" + userId, aiResponse);

// 限制最近 N 条
redis.opsForList().trim("chat:" + userId, -10, -1);

// 获取上下文
List<String> history = redis.opsForList().range("chat:" + userId, 0, -1);
```

**3. 向量数据库方案（语义检索）**
```java
// 对话嵌入向量
float[] embedding = embeddingModel.embed(currentMessage);

// 检索相似对话
List<SimilarConversation> similar = chromaClient.query(
    collectionId, 
    embedding, 
    topK=5
);

// 构建 prompt
Prompt prompt = new Prompt(
    new SystemMessage("参考相似对话:\n" + similar),
    new UserMessage(currentMessage)
);
```

**4. Spring AI ChatMemory（推荐）**
```java
@Bean
public ChatMemory chatMemory() {
    return new MessageWindowChatMemory(10);  // 保留最近 10 条
}

@PostMapping("/chat")
public Flux<String> chat(@RequestBody ChatEntity chatEntity) {
    return chatClient.prompt()
        .advisors(new MessageChatMemoryAdvisor(chatMemory, chatEntity.getCurrentUserName()))
        .user(chatEntity.getMessage())
        .stream()
        .content();
}
```

**面试加分点：**
> 大厂使用 LangChain 的 ConversationBufferMemory + 向量数据库实现长期记忆，短期用 Redis，长期用 ES/向量库。

---

### Q30: 如果要支持多租户（多个家庭），如何改造？

**答案：**

**改造方案：**

**1. 数据库改造**
```sql
-- 添加租户 ID
ALTER TABLE chat_record ADD COLUMN tenant_id VARCHAR(50);
CREATE INDEX idx_tenant_member ON chat_record(tenant_id, family_member);

-- 行级权限
SELECT * FROM chat_record 
WHERE tenant_id = ? AND family_member = ?;
```

**2. 代码改造**
```java
// ChatRecord 实体
@Data
public class ChatRecord {
    private String id;
    private String tenantId;      // 新增
    private String familyMember;
    private String content;
    private LocalDateTime chatTime;
    private String chatType;
}

// Service 层
public void saveChatRecord(String tenantId, String userName, String message, ChatTypeEnum type) {
    ChatRecord record = new ChatRecord();
    record.setTenantId(tenantId);
    record.setFamilyMember(userName);
    ...
}

// 查询加租户过滤
public List<ChatRecord> getChatRecordList(String tenantId, String userName) {
    LambdaQueryWrapper<ChatRecord> wrapper = Wrappers.lambdaQuery();
    wrapper.eq(ChatRecord::getTenantId, tenantId)
           .eq(ChatRecord::getFamilyMember, userName);
    return chatRecordMapper.selectList(wrapper);
}
```

**3. 租户隔离方案**
| 方案 | 说明 | 适用场景 |
|------|------|----------|
| **字段隔离** | tenant_id 字段区分 | 小租户，成本低 |
| **Schema 隔离** | 每个租户独立 Schema | 中型租户，数据隔离 |
| **数据库隔离** | 每个租户独立 DB | 大型租户，完全隔离 |

**4. 租户管理**
```java
// 租户实体
@Data
public class Tenant {
    private String id;
    private String name;
    private String status;  // ACTIVE/SUSPENDED
    private LocalDateTime createTime;
}

// 租户上下文
ThreadLocal<String> tenantContext = new ThreadLocal<>();
// 拦截器设置
tenantContext.set(request.getHeader("X-Tenant-ID"));
```

**面试加分点：**
> 大厂使用 MyBatis-Plus 的 `TenantLineInnerInterceptor` 自动注入租户条件，配合 ShardingSphere 实现多租户分库分表。

---

### Q31: 如果要部署到 Kubernetes，需要注意什么？

**答案：**

**K8s 部署要点：**

**1. Dockerfile**
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/deepseek-doctor.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**2. Deployment**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: deepseek-doctor
spec:
  replicas: 3
  selector:
    matchLabels:
      app: deepseek-doctor
  template:
    metadata:
      labels:
        app: deepseek-doctor
    spec:
      containers:
      - name: app
        image: deepseek-doctor:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: OLLAMA_BASE_URL
          value: "http://ollama:11434"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /hello/world
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /hello/world
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
```

**3. Service**
```yaml
apiVersion: v1
kind: Service
metadata:
  name: deepseek-doctor
spec:
  selector:
    app: deepseek-doctor
  ports:
  - port: 80
    targetPort: 8080
  type: ClusterIP
```

**4. Ingress**
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: deepseek-doctor-ingress
  annotations:
    nginx.ingress.kubernetes.io/affinity: "cookie"  # 会话粘性
    nginx.ingress.kubernetes.io/session-cookie-name: "route"
spec:
  rules:
  - host: doctor.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: deepseek-doctor
            port:
              number: 80
```

**5. 注意事项**
- **会话粘性** - SSE 需要 sticky session（Ingress 注解）
- **健康检查** - liveness/readiness probe
- **资源限制** - requests/limits 防止 OOM
- **配置管理** - ConfigMap/Secret 管理配置
- **日志收集** - 使用 EFK/ELK 栈
- **监控** - Prometheus + Grafana

**面试加分点：**
> K8s 使用 HPA（Horizontal Pod Autoscaler）自动伸缩，基于 CPU/内存或自定义指标（如 QPS）。SSE 长连接需要调整 `terminationGracePeriodSeconds` 优雅关闭。

---

## 📝 总结

### 核心技术点回顾

| 领域 | 关键技术 | 面试频率 |
|------|----------|----------|
| **Spring Boot** | 自动装配、启动流程、Bean 生命周期 | ⭐⭐⭐⭐⭐ |
| **Spring AI** | Ollama 集成、流式输出、上下文记忆 | ⭐⭐⭐⭐ |
| **SSE** | 原理、连接管理、集群方案 | ⭐⭐⭐⭐ |
| **MyBatis-Plus** | CRUD、条件构造、分页 | ⭐⭐⭐⭐ |
| **并发编程** | ConcurrentHashMap、AtomicInteger、volatile | ⭐⭐⭐⭐⭐ |
| **数据库** | 索引、分库分表、优化 | ⭐⭐⭐⭐⭐ |
| **系统设计** | 高可用、集群、K8s | ⭐⭐⭐⭐ |
| **性能优化** | 响应时间、内存、CPU | ⭐⭐⭐⭐ |
| **问题排查** | CPU、OOM、慢接口 | ⭐⭐⭐⭐⭐ |

### 面试建议

1. **项目介绍** - 3 分钟讲清楚背景、技术栈、你的贡献
2. **技术深度** - 每个技术点能讲出原理 + 实践
3. **问题排查** - 线上问题有系统的排查思路
4. **系统设计** - 能画出架构图，讲出权衡取舍
5. **代码能力** - 手写核心代码（如 SSE 连接管理）

---

**文档生成：** 麻辣大龙虾 🦞  
**生成时间：** 2026-03-11 22:28 GMT+8  
**用途：** 大厂面试准备

---

## 🎯 如何使用这份文档

1. **通读一遍** - 了解整体知识框架
2. **标记重点** - 标出你不太熟悉的部分
3. **针对性学习** - 查漏补缺
4. **模拟面试** - 自问自答，录音回放
5. **实战演练** - 在项目中实践这些技术

**祝你面试顺利！🦞**

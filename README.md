# 🌟 Lumina-RAG-Starter：次世代企业级 Agentic RAG 核心引擎

![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.x-blue.svg) ![LangChain4j](https://img.shields.io/badge/LangChain4j-0.31.0-blue.svg) ![MCP](https://img.shields.io/badge/Protocol-MCP-orange.svg) ![LangGraph](https://img.shields.io/badge/Workflow-LangGraph-red.svg) ![Langfuse](https://img.shields.io/badge/LLMOps-Langfuse-purple.svg) ![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)

Lumina 是一个专为**高并发、强一致性、低延迟**而生的企业级 Agentic RAG（检索增强智能体）Spring Boot Starter。

它不仅仅是对大模型 API 的简单封装，而是将**工具级语义多级缓存、底层并发护城河、动态长上下文溯源（Small-to-Big）、MCP 标准化协议、LangGraph 状态机工作流编排与 OpenTelemetry 全链路可观测性**等大厂真实落地的底层架构，封装为开箱即用的组件。只需引入一行依赖，即可瞬间赋予你的项目百万级高可用 AI 架构！

---

## ✨ 核心硬核特性 (Core Features)

### 1. 🧠 Agent 智能体大脑与动态上下文路由 (Dynamic Context Routing)
彻底抛弃死板的 RAG 检索流水线。Lumina 内建 ReAct 模式的智能体大脑，支持纯正向 Prompt 约束，自动拆解意图：
- **微观/宏观动态路由**：大模型自主决定是否拉取长文。若是细节问题，采用高精度切片（Short RAG）；若是总结问题，触发 `Small-to-Big`，瞬间从 Redis 拔出数万字完整源文档！
- **分布式私有记忆**：结合 Redis 聊天记忆存储，打造跨集群、多节点部署的无状态 (Stateless) 引擎，保证多轮对话永不失忆。

### 2. ⚡ V8 级双引擎混合检索 (Hybrid Search)
彻底解决传统检索召回率与准确率的矛盾：
- **广义召回 + 精度定杀**：底层 Elasticsearch 采用**“BM25 词法广义召回” + “HNSW 稠密向量（384维）Painless 余弦相似度精度定杀”**。
- **纯净实体抽取**：结合 Agent 提取的纯净实体 Keyword 检索，既防幻觉，又完美容忍自然语言的词汇鸿沟。

### 3. 🛡️ 工具级防击穿护城河 (Tool-Level Singleflight) & 多级缓存
业界首创将并发锁与缓存**下沉至 `@Tool` 级别**，彻底解放网关：
- **防缓存击穿**：内建 Singleflight 内存级单例锁。万名用户同时并发相同问题，底层仅放行一个“先锋线程”去查 ES，其余线程优雅挂起并秒级共享客观结果，保护数据库免受海啸级 I/O 冲击。
- **绝对的数据安全**：网关层 0 缓存，仅在 Tool 层缓存最纯净的客观真理（L1 Redis 7ms / L2 ES 50ms），从物理上彻底杜绝多租户大模型对话情况下的“隐私泄露”与“串库”风险。

### 4. 🔒 基于 OOP 的绝对参数隔离 (Thread-Safe Isolation)
在处理大模型异步网络回调时，传统的 `ThreadLocal` 极易引发上下文丢失。Lumina 采用**面向对象级作用域 (Request-Scoped Tool Instances)**，将 `indexName` (库名) 和权限过滤器直接封存在专属 Tool 实例中，穿透一切异步调用，保证多租户路由 100% 绝对物理安全！

### 5. 🔗 物理级数据一致性与 Kafka GC 闭环
业务层更新/删除源文档时，发送单条 Kafka 消息，Lumina 底层立刻提取血缘 `ParentID`，在全网所有节点**精准、物理级炸毁**依赖该文档的 L1/L2 AI 问答缓存，永远告别“文档已删，AI 还在乱报旧数据”的脏读灾难。

### 6. 👁️ 内建 LLMOps 级可观测性 (Langfuse Integration)
拒绝 AI 黑盒！框架深度集成 Langfuse 监听器。大模型的每一次思考流转、工具调用的毫秒级耗时、输入输出的 Token 级计费，皆在云端后台生成完美的瀑布流甘特图（Trace），尽在掌控。

### 7. 🔌 MCP 标准化协议 (Model Context Protocol)
告别碎片化集成！Lumina 内建 MCP Server 微服务，基于 **SSE 长连接 + JSON-RPC 2.0** 标准传输层，将 RAG 检索能力标准化暴露为可发现工具接口。Claude Desktop、Claude Code 等 MCP 客户端可零配置动态发现并调用知识库检索能力，实现 AI 助手与底层数据引擎的解耦集成。

### 8. 🧩 LangGraph 状态机工作流编排 (Stateful Workflow Engine)
超越单次检索！Lumina 自研轻量级 **LangGraph 有状态图引擎**，将意图分类、关键词提取、知识库检索与答案生成建模为 **Node-Edge 有向图工作流**。支持条件路由与多步推理的动态编排，将简单的 "检索→回答" 升级为可编排的 **Agentic 多步推理流水线**，且每一步的状态变化均可追踪、可调试。

---

## 🚀 极速上手 (Quick Start)

### 1. 引入依赖
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.Gastertablook.lumina-framework</groupId>
    <artifactId>lumina-rag-core</artifactId>
    <version>v1.0.0</version>
</dependency>
```

### 2. 填写配置 (application.yml)
```yaml
spring:
  redis:
    host: 127.0.0.1
  elasticsearch:
    uris: http://127.0.0.1:9200
    
langchain4j:
  open-ai:
    streaming-chat-model:
      base-url: https://open.bigmodel.cn/api/paas/v4/
      api-key: your-api-key-here # 支持兼容 OpenAI 协议的任意大模型
      model-name: glm-4-flash
      timeout: PT120S 
```

### 3. 一行代码呼叫神龙
在业务应用中直接注入 `LuminaRagClient`：
```java
@Autowired
private LuminaRagClient luminaRagClient;

@PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
public SseEmitter chat(@RequestBody ChatRequest request) {
    // 一行代码，防并发、多级缓存、Agent意图识别、长文溯源、流式输出全自动在后台运转！
    return luminaRagClient.chatStream(
            request.getQuery(), 
            request.getSessionId(), 
            request.getIndexName(), 
            request.getMetadataFilters()
    );
}
```

### 4. 📚 核心 API 使用指南 (全生命周期闭环)

Lumina 将极其复杂的底层机制封装为了三大核心 API。无论你在应用层使用什么数据库，只需调用以下 API 即可完成业务闭环：

#### 场景一：文档安全摄入 (Ingestion)
当从物理文件中提取出文本后调用此 API。Lumina 后台全自动完成：`策略切块 -> 向量化 -> 存Redis父文档 -> 存ES碎片 -> 建立安全血缘烙印`。
```java
@Autowired
private DocumentIngestionEngine documentIngestionEngine;

// 返回值 parentId：全局唯一血缘 ID，请务必将其存入你的 MySQL 业务表中！
String parentId = documentIngestionEngine.ingest("年终总结.pdf", "长文本内容...", "tenant_workspace_01");
```

#### 场景二：物理销毁与缓存一致性爆破 (Deletion & Cache GC)
业务层文档被删除/更新时必须调用。Lumina 将极其冷酷地执行：`删除 ES 碎片 -> 删除 Redis 长文`。配合 Kafka 在应用层的广播，物理炸毁依赖过该文档的全网所有 L1/L2 缓存。
```java
@Autowired
private DocumentIngestionEngine documentIngestionEngine;

documentIngestionEngine.removeDocument("tenant_workspace_01", "doc_abcd123456789...");
```

---

## 🗺️ 未来演进路线图 (Roadmap)

- [x] **Milestone 1-4**: 核心 RAG 引擎、并发护城河与 Agentic 架构封神。
- [x] **Milestone 5**: CMS 业务闭环落地（MySQL 真实落盘与 Apache PDFBox 多模态解析）。
- [x] **Milestone 6**: 接入 Langfuse LLMOps，点亮 Agent 思考链路可观测性天眼。
- [x] **Milestone 7**: 协议升维，封装 MCP (Model Context Protocol) 标准 Server 微服务生态插座。
- [x] **Milestone 8**: 重塑大脑，引入 LangGraph 状态机编排，实现复杂反思（Self-Reflection）工作流。
- [ ] **Milestone 9**: 激活 `DocumentProvider` SPI 扩展点，全自动装配 Excel, PPT, Html 多模态粉碎机。

---
> *Lumina: 旨在把极致的系统复杂度留在框架内，把极致的优雅留给业务开发者。*
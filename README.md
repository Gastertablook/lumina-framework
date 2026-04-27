# 🌟 Lumina-RAG-Starter：企业级 Agentic RAG 核心引擎

![JDK](https://img.shields.io/badge/JDK-17+-green.svg) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.x-blue.svg) ![LangChain4j](https://img.shields.io/badge/LangChain4j-0.31.0-blue.svg) ![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)

Lumina 是一个专为**高并发、强一致性、低延迟**而生的企业级 Agentic RAG（检索增强智能体）Spring Boot Starter。

它不仅仅是对大模型 API 的简单封装，而是将**分布式多级语义缓存、高并发防击穿护城河、动态长上下文溯源（Small-to-Big）**等大厂真实落地的底层架构封装为开箱即用的组件。只需引入一行依赖，即可为你的项目注入千万级架构的 AI 能力。

---

## ✨ 核心特性 (Core Features)

### 1. 🧠 Agent 智能体大脑与动态路由 (Dynamic Routing)
抛弃死板的 RAG 检索流水线。Lumina 内建 ReAct 模式的智能体大脑，支持纯正向 Prompt 约束，自动拆解意图：
*   **意图自动分发**：自动识别用户的日常闲聊、基础算术与业务查询。闲聊走内部知识（0 消耗），业务查询精确路由至底层数据工具。
*   **微观/宏观动态路由**：大模型自主决定是否拉取长文。若是细节问题，采用高精度切片（Short RAG）；若是总结问题，触发 `Small-to-Big`，瞬间从 Redis 拔出数万字完整源文档！
*   **私有记忆漫游**：内置基于 Redis 的分布式聊天记忆（Chat Memory）机制，跨集群多节点部署也能保证用户多轮对话永不失忆。

### 2. ⚡ 毫秒级多级语义缓存引擎 (Multi-level Semantic Cache)
大模型的生成速度和成本是企业落地的最大痛点。Lumina 构建了坚不可摧的缓存防线：
*   **L1 极速内存拦截 (Redis)**：毫秒级响应完全相同的请求。
*   **L2 语义防线 (Elasticsearch)**：基于 HNSW 稠密向量（384维）计算余弦相似度。即使提问措辞不同，只要语义一致（阈值 > 0.85），立刻拦截并返回底层客观数据。
*   **租户级物理隔离**：缓存深植于 `@Tool` 层面，彻底杜绝多租户/多会话情况下的“身份泄露”与“串库”风险。

### 3. 🛡️ 高并发护城河 (Singleflight Deduplication)
应对双十一级别的高并发爆刷：内存级单例锁（Singleflight）保证在同一会话下，多个相同的并发请求只允许 1 个去调用昂贵的大模型 API，其余线程优雅挂起并共享先锋计算结果，彻底杜绝 API 计费黑洞与 Tomcat 线程池耗尽。

### 4. 🔍 动态长文档溯源 (Small-to-Big Retrieval)
解决传统 RAG “切块导致语义断裂”的绝症：
*   底层 ES 执行 高精度词法+向量检索，命中切块碎片。
*   通过 `ParentID` 血缘追踪，瞬间从 Redis 拔出原属的数万字完整长文档，喂给大模型进行拥有“上帝视角”的长上下文推理。

### 5. 🔗 分布式数据一致性与垃圾回收 (Cache Invalidation)
在真实的 CMS 系统中修改或删除源文档时，只需发送单条 Kafka 消息，Lumina 底层立刻顺藤摸瓜，在全网所有节点**物理级炸毁**依赖了该文档的 L1/L2 缓存，永远告别 AI 提供脏数据的事故。

### 6. 🧩 极强的 SPI 扩展性 (Highly Extensible)
Lumina 绝不将业务逻辑写死，框架提供丰富的 SPI 接口供开发者复写重构：
*   `DocumentSplitterStrategy`：自定义文本切分策略（框架默认提供 `递归 500 字重叠切块`，业务方可自由复写为按法律条款、按 Markdown 标题、代码类/方法等方式切块）。
*   `DocumentIngestionEngine`：自定义文档摄入黑盒引擎，可无缝对接 PDFBox、Apache POI 处理多模态文件。

---

## 🎯 适用场景 (Use Cases)

*   **企业内部智能知识库**：结合微服务架构，构建类似于 NotebookLM 的私有化文档交互平台。
*   **智能电商导购**：将商品长图文和评价灌入系统，提供精准的商品对比与推荐。
*   **医疗/法律研报分析**：利用系统的 Small-to-Big 特性，对几万字的法律合同和研报进行无损宏观总结。
*   **等等**

---

## 🚀 极速上手 (Quick Start)

### 1. 引入依赖 (基于 JitPack)
在任何 Spring Boot 项目中引入：
```xml
<dependency>
    <groupId>com.github.YourGithubName</groupId>
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
      api-key: your-api-key-here # 支持任意兼容 OpenAI 协议的模型(如智谱、DeepSeek)
      model-name: glm-4-flash
      timeout: PT120S 
```

### 3. 一行代码呼叫神龙
在你的 Controller 中注入 `LuminaRagClient`：
```java
@Autowired
private LuminaRagClient luminaRagClient;

@PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
public SseEmitter chat(@RequestBody ChatRequest request) {
    // request 包含 query, sessionId, indexName, metadataFilters
    // 防并发、缓存拦截、Agent意图识别、长文溯源、流式输出已在后台全自动运转！
    return luminaRagClient.chatStream(
            request.getQuery(), 
            request.getSessionId(), 
            request.getIndexName(), 
            request.getMetadataFilters()
    );
}
```
**就这么简单！防并发、缓存拦截、Agent意图识别、长文溯源、流式打字机输出已在后台全自动运转！**

---

## 🗺️ 未来演进路线图 (Roadmap)

- [x] **Milestone 1-4**: 核心 RAG 引擎与 Agentic 架构封神。
- [x] **Milestone 5**: CMS 业务闭环落地（包含 MySQL 真实落盘与 Apache PDFBox 多模态解析）。
- [ ] **Milestone 6**: 接入 Anthropic Claude 等模型，实现框架级显式的 `Prompt Caching` 拦截器。
- [ ] **Milestone 7**: 扩充 `@Tool` 生态库，引入 `WebSearchTool` 与 `Text2SQLTool`。
- [ ] **Milestone 8**: 激活 `DocumentProvider` SPI 扩展点，全自动装配 Excel, PPT, Html 多模态粉碎机。

---
> *Lumina: 把极致的复杂留给系统，把极致的优雅留给开发者。*
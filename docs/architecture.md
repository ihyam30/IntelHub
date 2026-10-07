# IntelHub 系统架构

## 部署视图

~~~mermaid
flowchart LR
    Browser["浏览器：Vue 3"] --> Web["Nginx 静态站点 / API 反向代理"]
    Web --> A["Spring Boot backend-a"]
    Web --> B["Spring Boot backend-b"]
    A --> PG[("PostgreSQL + pgvector")]
    B --> PG
    A --> Redis[("Redis：FIFO 队列、租期、熔断状态")]
    B --> Redis
    A --> ES[("Elasticsearch + IK")]
    B --> ES
    A --> S3[("MinIO：原始文件")]
    B --> S3
    A --> MQ["RocketMQ"]
    B --> MQ
    MQ --> A
    MQ --> B
    A --> MCP["MCP Server：演示工具"]
    B --> MCP
    A -. "可选 OpenAI-compatible Chat / Embedding" .-> AI["外部模型供应商"]
    B -. "可选 OpenAI-compatible Chat / Embedding" .-> AI
~~~

Compose 中两个后端共享 PostgreSQL、Redis、Elasticsearch、MinIO 和 RocketMQ。Nginx 将 API 请求转发给两个后端，并关闭响应缓冲以支持 SSE。两个实例共享 Redis 限流及熔断状态；文档入库由 RocketMQ 异步分发。

## 入库链路

~~~mermaid
sequenceDiagram
    actor U as 用户
    participant V as Vue / Nginx
    participant API as Spring Boot
    participant S3 as MinIO
    participant DB as PostgreSQL
    participant MQ as RocketMQ
    participant P as Pipeline Consumer
    participant ES as Elasticsearch
    U->>V: 上传 PDF / DOCX / DOC / Markdown / TXT
    V->>API: POST /api/knowledge-bases/{id}/documents
    API->>S3: 保存原始对象
    API->>DB: 写文档元数据与 QUEUED 状态
    API->>MQ: 投递文档任务
    API-->>V: 返回排队状态
    MQ->>P: 异步消费
    P->>P: parse → normalize → chunk → metadata
    P->>DB: embedding → 写入 pgvector
    P->>ES: 写入 BM25 关键词索引
    P->>DB: 更新节点运行记录与文档状态
    V->>API: 轮询文档状态和入库运行记录
    API-->>V: COMPLETED / FAILED 与节点日志
~~~

## 问答链路

~~~mermaid
sequenceDiagram
    actor U as 用户
    participant V as Vue / Nginx
    participant API as Spring Boot
    participant R as Redis Fair Queue
    participant DB as PostgreSQL + pgvector
    participant ES as Elasticsearch
    participant M as Chat Model Router
    participant MCP as MCP Server
    U->>V: 选择知识库并提问
    V->>API: POST /api/chat/stream
    API->>R: 用户配额、FIFO 排队与并发租期
    API->>DB: 校验知识库权限、会话记忆与意图
    par 双路召回
        API->>DB: 向量相似度检索
        API->>ES: BM25 关键词检索
    end
    API->>API: 去重、RRF 融合、可选 Rerank
    opt 问题命中演示工具
        API->>MCP: 发现 / 调用工具
        MCP-->>API: 工具结果
    end
    API->>M: 主模型生成，熔断时切换候选模型
    API-->>V: SSE 状态、引用、回答片段、完成或错误
    API->>DB: 保存会话、引用和 Trace
    API->>R: 释放并发租期
~~~

## 关键取舍

- 采用模块化单体：适合单人开发和本地 Compose 演示，业务模块之间仍通过清晰接口组织。
- PostgreSQL 同时承载业务数据和 pgvector；避免引入独立向量数据库，作品集规模部署更简单。极大规模下向量索引的独立扩缩能力较弱。
- Elasticsearch 负责中文 BM25 检索，补充向量检索对编号、关键词和专有名词的不足。
- RocketMQ 将文件解析与索引从上传请求中解耦；Redis 提供跨实例的公平排队、许可租期和模型熔断状态。
- Chat、Embedding 和 Rerank 使用显式适配接口；无凭据时可演示本地回退路径，但本地确定性 Embedding 与摘要式回答不代表真实模型质量。
- MinIO 保存上传文件；数据库保留文档、分块、会话、运行日志和 Trace 元数据。

## Compose 服务和端口

| 服务 | 用途 | 默认暴露端口 |
|---|---|---|
| frontend | Vue 页面与 Nginx API 代理 | 8080 |
| backend-a / backend-b | 两个 Spring Boot 实例 | 仅 Compose 网络 |
| postgres | 业务数据和向量 | 仅 Compose 网络 |
| redis | 队列、限流与熔断共享状态 | 仅 Compose 网络 |
| elasticsearch | BM25 索引 | 仅 Compose 网络 |
| minio | 原始文件和控制台 | 控制台 19001；S3 API 仅 Compose 网络 |
| namesrv / broker | RocketMQ | 仅 Compose 网络 |
| mcp-server | 示例工具协议服务 | 仅 Compose 网络 |

端口可以通过 .env 中的 FRONTEND_PORT 和 MINIO_CONSOLE_PORT 调整。

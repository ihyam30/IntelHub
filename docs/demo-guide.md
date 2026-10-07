# IntelHub 本地演示与配置指南

本指南说明 Compose 服务的配置与启动，并提供“注册 → 上传 → Pipeline → 混合检索 → SSE 回答 → Trace”的可重复演示。目标是本地作品集验收，不代表生产部署手册。2026-10-05 已在独立 Compose 项目和全新命名卷上完成启动及端到端演示验收；如默认 MinIO 镜像仓库不可访问，可通过 `MINIO_IMAGE` 指定可用且可信的兼容镜像。

## 1. 前置条件

- Windows/macOS/Linux 均可；Windows 建议使用 PowerShell 7。
- Docker Desktop 已启动，并启用 Docker Compose v2。
- 首次构建需要拉取容器镜像、Maven 依赖和 Elasticsearch IK 插件，需能访问相应镜像与依赖源。
- 建议为 Docker 分配至少 8 GB 内存；Elasticsearch 与双 Java 实例是主要内存使用方。
- 可选：Chat、Embedding、Rerank 供应商的兼容 API 地址和密钥。未配置 Chat 时仍可演示证据摘要式回答；未配置 Embedding 时使用本地确定性向量，检索效果有限。

## 2. 环境配置

从仓库根目录复制模板：

~~~powershell
Copy-Item .env.example .env
~~~

演示前检查 .env：

| 配置 | 是否必需 | 说明 |
|---|---|---|
| DATABASE_PASSWORD | 建议修改 | PostgreSQL 密码。 |
| REDIS_PASSWORD | 可选 | 设置后 Compose 会为 Redis 启用密码。 |
| MINIO_ROOT_USER / MINIO_ROOT_PASSWORD | 建议修改 | MinIO 管理账户。 |
| MINIO_IMAGE | 可选 | 默认使用按版本固定的 MinIO 社区重建镜像 `alpine/minio:RELEASE.2025-10-15T17-29-55Z`；可覆盖为组织信任的兼容镜像。镜像构建说明见 [alpine-docker/minio](https://github.com/alpine-docker/minio)。 |
| FRONTEND_PORT | 可选 | 前端宿主机端口，默认 8080。 |
| MINIO_CONSOLE_PORT | 可选 | MinIO Console 宿主机端口，默认 19001。 |
| AI_PRIMARY_BASE_URL / AI_PRIMARY_API_KEY / AI_PRIMARY_MODEL | 可选 | Chat 主模型的 OpenAI-compatible 地址、密钥和模型名。三项一起配置。 |
| AI_FALLBACK_BASE_URL / AI_FALLBACK_API_KEY / AI_FALLBACK_MODEL | 可选 | Chat 备用模型；配置方式同主模型。 |
| AI_EMBEDDING_BASE_URL / AI_EMBEDDING_API_KEY / AI_EMBEDDING_MODEL | 可选 | Embedding 供应商；模型输出维度需与 EMBEDDING_DIMENSION 一致（默认 384）。 |
| AI_RERANK_BASE_URL / AI_RERANK_API_KEY / AI_RERANK_MODEL | 可选 | Cohere-compatible Rerank。 |
| INTELHUB_ADMIN_USERNAME / PASSWORD | 可选 | 启动时引导创建管理员；密码满足注册校验长度。未设置时没有预置管理员。 |
| CHAT_MAX_CONCURRENT | 可选 | 跨实例问答最大并发许可数，默认 4。 |
| CHAT_MAX_WAIT_SECONDS | 可选 | 排队等待上限，默认 30 秒。 |
| CHAT_PERMIT_LEASE_SECONDS | 可选 | 并发许可租期，默认 600 秒。 |
| CHAT_MODEL_FAILURE_THRESHOLD / CHAT_MODEL_CIRCUIT_COOLDOWN_MILLIS | 可选 | 主模型熔断连续失败阈值与冷却时间，默认 3 次和 30000 毫秒。 |
| RAG_MIN_VECTOR_SIMILARITY / RAG_MIN_LEXICAL_OVERLAP | 可选 | 向量相似度与关键词重合度过滤阈值，默认 0.25 和 0.1。 |
| ROCKETMQ_SEND_MESSAGE_TIMEOUT | 可选 | 入库消息投递超时，默认 10000 毫秒。 |

修改 MinIO 镜像、模型或管理员引导配置后，重建对应服务以应用设置：

~~~powershell
docker compose up -d --force-recreate minio backend-a backend-b
~~~

不要将包含真实密钥的 .env 提交到版本库；演示用密码也不要复用真实环境凭据。

## 3. 启动和健康检查

在仓库根目录执行：

~~~powershell
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 900
docker compose ps
Invoke-WebRequest http://localhost:8080 -UseBasicParsing
Invoke-RestMethod http://localhost:8080/api/health/ready
~~~

验收时确认 backend-a、backend-b 均显示运行且健康检查通过；首页与 readiness 返回 HTTP 200。若默认端口冲突，可在 .env 设置 FRONTEND_PORT，再按该端口访问前端。Compose 配置定义两个后端服务；只有执行 up 后并实际检查容器健康，才算完成双实例运行态验收。

### 可选：空数据卷启动验收

用一个从未使用过的 Compose 项目名启动，可让 Docker 为该项目创建全新的命名卷，不会复用默认 `intelhub` 项目的数据库或文件：

~~~powershell
$cleanProject = "intelhub-clean-check-$(Get-Date -Format yyyyMMddHHmmss)"
docker compose --env-file .env -p $cleanProject up --build -d --wait --wait-timeout 1200
docker compose --env-file .env -p $cleanProject ps
~~~

确认双后端及依赖服务健康、Flyway 从空 schema 完成迁移后，可对该栈运行端到端演示。验收完成后，以下命令只移除这个隔离项目及其专属卷；不要把项目名改成正在使用的默认 `intelhub`：

~~~powershell
docker compose --env-file .env -p $cleanProject down --volumes --remove-orphans
~~~

主要入口：

- IntelHub：默认 http://localhost:8080
- MinIO Console：默认 http://localhost:19001
- 后端 API 经由前端 Nginx 代理，以 /api 开头；后端实例没有映射到宿主机端口。

## 4. 端到端演示

### 方式 A：浏览器手动演示

1. 打开 IntelHub，注册一个普通账号并登录。
2. 进入知识库页面，创建“员工制度演示库”。
3. 上传 demo-data/employee-leave-policy.md，等待状态变成 COMPLETED，确认分块数量大于 0。
4. 进入聊天页选择刚创建的知识库，提问：“员工请假制度中，年假申请需要经过哪些步骤？”
5. 确认页面出现检索阶段、来源引用和回答；答案应基于上传的演示文件。未配置外部 Chat 时，回答为本地证据摘要式文本。
6. 进入管理页 Trace，或查看当前账号的问答记录，确认最新记录状态为 COMPLETED，向量/关键词检索信息与引用数量已写入。
7. 若配置了管理员引导账号，可用该账号查看用户、模型状态、入库 Pipeline、工具和 Trace 管理界面。

示例文件内容是虚构的演示制度，不应被当作真实公司政策。

### 方式 B：PowerShell API 演示

脚本会尝试按输入账号注册；若用户名已存在，则使用输入密码登录。脚本随后创建一个新的知识库、上传示例 Markdown，等待异步入库完成，发送 POST SSE 问题，并打印引用与 Trace。脚本会保留本次知识库和 Trace，便于在页面中查看。

~~~powershell
pwsh -NoProfile -File .\scripts\demo.ps1
~~~

参数示例：

~~~powershell
pwsh -NoProfile -File .\scripts\demo.ps1 -BaseUrl http://localhost:18082 -Question "员工请假制度中，年假申请需要经过哪些步骤？"
~~~

脚本交互中输入要注册或登录的用户名和密码。用户名须为 3–64 个字母、数字、点、下划线或连字符；密码为 10–128 位。默认问题应匹配示例文档中的内容。

脚本会新增一个知识库、文档、问答会话和 Trace。完成演示后，可在页面删除知识库；测试账号可由管理员在用户管理页停用或删除。要彻底清空所有本地演示数据，需明确执行 docker compose down -v，该命令会删除本项目所有持久化卷。

## 5. 双实例演示观察点

- docker compose ps backend-a backend-b 显示两个服务健康。
- 两实例连接同一 Redis、PostgreSQL、Elasticsearch 和 RocketMQ。
- 管理页的并发许可设置反映 Redis 共享许可状态；请求可由 Nginx 分配到任一后端。
- Pipeline 节点运行记录、问答引用和 Trace 可在页面查看。
- 该 Compose 是本地演示栈，不包含生产级数据库高可用、TLS、秘密管理或 SLA 配置。

## 6. 常见问题

| 现象 | 检查方式 |
|---|---|
| Docker 命名管道 / daemon permission denied | 启动 Docker Desktop，确认当前终端用户有权连接 Docker Engine，再运行 docker compose ps。 |
| 前端 502 或 readiness 尚未通过 | 等待 backend-a 和 backend-b 完成数据库迁移、模型和检索客户端初始化；用 docker compose logs backend-a backend-b 查看日志。 |
| 上传后持续 QUEUED | 检查 namesrv、broker 健康状态以及 backend 日志中的 RocketMQ 连接错误。 |
| 文档为 FAILED | 查看文档错误信息和 Pipeline 节点日志；修复环境后通过页面的重试操作重新入库。 |
| 答案没有引用 | 确认文档状态为 COMPLETED、问题与示例内容相关；查看 Trace 中向量/关键词通道和过滤结果。 |
| Chat 没有调用外部供应商 | 检查 .env 中主模型地址、密钥、模型名均已设置，并重建两个后端。 |
| Elasticsearch 无法加载 IK | 镜像构建需下载 IK 插件；若不可用，应用的关键词检索会按项目配置回退到标准分词。 |

## 7. 验收清单

- [ ] Compose 配置可解析，依赖、MCP、双后端和前端均启动。
- [ ] backend-a、backend-b 的健康检查都通过；前端与 readiness 返回 HTTP 200。
- [ ] 注册、登录、当前用户查询成功。
- [ ] 示例 Markdown 上传后完成六节点入库，状态为 COMPLETED，分块数大于 0。
- [ ] POST SSE 问答返回回答片段、至少一个引用以及 done 事件。
- [ ] Trace 中可找到该请求，状态为 COMPLETED，且记录检索通道和引用数。
- [ ] 普通用户不能访问其他用户的知识库；如启用管理员引导，管理员管理入口可用。
- [ ] README、配置指南、架构图、示例数据和演示脚本均在交付包中。

Compose 无法连接 Docker Engine 时，只能完成配置静态检查和自动化测试；不得把整栈双实例运行验收勾选为通过。

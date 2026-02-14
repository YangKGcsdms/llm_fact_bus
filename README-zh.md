# Fact Bus

Fact Bus 是一个面向 LLM/Agent 系统的事件驱动治理运行时。  
它通过以下机制，在概率性 Agent 之上构建确定性的系统行为：

- 契约优先事件（Contract-first events）
- 确定性仲裁（Deterministic arbitration）
- 执行到事实衍生（FDR）
- 可回放事件日志与状态投影

## 项目状态

- 当前阶段：**MVP**
- 运行时：**Spring Boot (Java 17)**
- 存储：**内存事件存储**（原型阶段）

## 核心流程

```text
Gateway -> FACT_EVENT
Agent -> PROPOSAL_EVENT / OBSERVATION_EVENT / TOOL_* / DIAGNOSTIC
Arbitrator -> DECISION_EVENT
Executor -> EXECUTION_EVENT
FDR (bus内置) -> 派生 FACT_EVENT
Projection -> confirmed_facts + pending_decisions + pending_executions
```

## API

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/v1/events` | 发布通过契约校验的事件 |
| `GET` | `/v1/events` | 按 trace/category/subject 查询事件 |
| `GET` | `/v1/events/stream` | SSE 实时订阅 |
| `POST` | `/v1/gateway/intents` | 冷启动入口，请求转 FACT_EVENT |
| `GET` | `/v1/projections/{subjectType}/{subjectId}` | 读取按 subject 聚合的投影 |

## MVP 能力

- 8 类事件 + schema-first 校验
- Producer-Category 权限矩阵校验
- 重复 `event_id` 拒绝（HTTP 409）
- 带结构化拒绝反馈的确定性仲裁
- FDR 链路：`EXECUTION_EVENT -> FACT_EVENT`
- 含在途状态的 Projection 视图
- 统一机器可读错误格式
- 单调递增 `sequence_number`

## 快速开始

### 1) 启动服务

```bash
mvn spring-boot:run
```

### 2) 健康检查

```bash
curl http://localhost:8080/actuator/health
```

### 3) 注入初始意图（冷启动）

```bash
curl -X POST http://localhost:8080/v1/gateway/intents \
  -H "Content-Type: application/json" \
  -d '{
    "intent_name": "RefundRequested",
    "subject": { "type": "order", "id": "ORD-1001" },
    "facts": { "order_id": "ORD-1001", "reason": "customer_request" },
    "source": "api"
  }'
```

### 4) 发布提议

```bash
curl -X POST http://localhost:8080/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "schema_version": "1.0.0",
    "event_id": "dc31a700-6436-4ad4-a137-74831241a2f6",
    "event_category": "PROPOSAL_EVENT",
    "event_name": "ProposedRefund",
    "occurred_at": "2026-02-14T09:00:05Z",
    "trace_id": "trace-ord-1001",
    "producer": { "type": "agent", "id": "refund-agent", "version": "v1" },
    "subject": { "type": "order", "id": "ORD-1001" },
    "payload": {
      "proposal_id": "prp-2001",
      "proposed_action": { "type": "refund", "amount": 1200 },
      "based_on_events": ["<initial_fact_event_id>"],
      "risk_level": "medium",
      "cost_estimate": 1200,
      "priority": 80,
      "max_fact_age_ms": 60000
    }
  }'
```

### 5) 查询投影

```bash
curl http://localhost:8080/v1/projections/order/ORD-1001
```

## 测试

当前包含：

- 契约校验测试
- 集成 Happy Path
- 拒绝后补充观测再提议链路
- 回放一致性测试

本地执行：

```bash
mvn test
```

## 错误格式

所有 API 错误统一返回：

```json
{
  "error_code": "CONTRACT_VIOLATION",
  "message": "payload.max_fact_age_ms is required",
  "timestamp": "2026-02-14T09:00:00Z"
}
```

常见错误码：  
`CONTRACT_VIOLATION`, `DUPLICATE_EVENT`, `BAD_REQUEST`, `INVALID_ARGUMENT`, `INTERNAL_ERROR`

## 目录结构

```text
src/main/java/com/factbus/
  api/           # REST 控制器与错误处理
  bus/           # 事件总线与存储
  contract/      # Envelope、枚举、校验器
  arbitration/   # 确定性仲裁
  fdr/           # 事实衍生反应器
  projection/    # 状态投影模型与服务
```

## 设计与契约文档

- `DESIGN.md`：系统治理架构规范
- `EVENT_CONTRACT_V0.md`：事件契约与字段字典
- `event-contract-v0.schema.json`：机器可校验 schema
- `LANGCHAIN_INTEGRATION.md`：Python/LangChain 接入说明

## `.gitignore` 说明

根目录 `.gitignore` 已排除：

- 构建产物（`target/`, `build/`, `out/`）
- IDE 本地文件（`.idea/`, `.vscode/`, `*.iml`）
- 系统临时文件（`.DS_Store`, `Thumbs.db`）
- 本地环境与密钥（`.env*`, `secrets/`）
- 日志与本地报告

用于保证仓库可复现，避免敏感信息误提交。

## 贡献指南

欢迎非商用场景下贡献：

1. 先提交 issue 说明问题/方案
2. 变更需与 `DESIGN.md` 与契约文档保持一致
3. 行为变更需补充/更新测试
4. PR 保持聚焦并给出动机说明

## 安全说明

- 当前 MVP 尚未提供生产级 authN/authZ
- 不要直接暴露到不受信任公网
- 不要提交任何密钥或生产凭据

## 许可证（非商用）

本项目使用 **CC BY-NC 4.0**（禁止商用）。

- 详情见 `LICENSE`
- 商业使用请联系维护者申请单独商业授权

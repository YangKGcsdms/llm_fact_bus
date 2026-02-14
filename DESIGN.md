# LLM 系统治理框架设计规范 (DESIGN.md)

## 1. 问题定义 (Problem Statement)

大语言模型 (LLM) 和基于 Agent 的系统将**概率性、非确定性**的行为引入了生产环境。

当前 AI 系统面临的核心挑战：
*   **决策不可复现**：同样的输入可能产生不同的结果。
*   **隐式状态与私聊**：Agent 间的私下协作导致系统状态不可观测。
*   **冲突时行为无界**：当多个 Agent 意见不一，系统缺乏确定的仲裁机制。
*   **缺乏审计与归因**：无法在事后对错误决策进行精确的因果追溯。

**本框架的目标不是提升模型智能，而是提供一个系统级的治理和控制平面，以约束熵增和不确定性。**

---

## 2. 核心设计原则 (Core Design Principles)

**不确定性必须在系统层（System Level）进行约束，而非模型层（Model Level）。**

本框架受工业总线架构（如 CAN 总线）启发，采用纯软件定义的事件驱动架构 (EDA) 实现。
*   **确定性行为**：在概率性 Agent 之上构建确定性的系统行为。
*   **显式冲突解决**：通过仲裁层而非 Prompt 解决冲突。
*   **全量可回放性**：系统状态必须是可观测、可追溯、可还原的。
*   **关注点分离**：推理（Agent）、事实（Bus）、仲裁（Policy）彻底解耦。

---

## 3. 架构概览 (Architectural Overview)

```text
+-------------------+
|     外部世界       |
+-------------------+
          |
          v
+-------------------+
|  入口网关/事实生产者 |  (传感器 / API / 数据库快照 / User Intent)
+-------------------+
          |
          v
+=================================================+
|                 事实总线 (FACT BUS)              |
|      (事实典籍 / 系统唯一真相来源 SSoT)            |
|   内含: 事实衍生反应器 FDR (确定性衍生规则)        |
+=================================================+
          |
          v
+------------------------+
| Agent Matrix Runtime   |  (LangGraph 多角色)
| Reasoning + MCP Tools  |
+------------------------+
   |        |         |
   |        |         +--> TOOL_CALL_EVENT / TOOL_RESULT_EVENT
   |        +------------> OBSERVATION_EVENT / DIAGNOSTIC_EVENT
   +---------------------> PROPOSAL_EVENT
                         |
                         v
                 +-------------------+
                 |     仲裁层         |
                 | (非 LLM, 基于策略) |
                 +-------------------+
                         |
                         v
                 +-------------------+
                 |   DECISION_EVENT   |
                 +-------------------+
                         |
                         v
                 +-------------------+
                 |     执行层         |
                 +-------------------+
                         |
                         v
                 +-------------------+
                 |  EXECUTION_EVENT   |
                 +-------------------+
                          |
                          v
                 +-------------------+
                 | FACT_EVENT 回流    |
                 | (由 Bus 内置 FDR)   |
                 +-------------------+
```

---

## 4. 事实总线 (Fact Bus / Event Canon)

### 4.1 定义
事实总线是系统的全局感知与记忆层。**如果一个信息没有出现在事实总线上，它在系统中就不存在。**

### 4.2 事件定义 (Event Definition)
Fact Bus 承载的是统一事件典籍（Event Canon），包含事实、提议、决策、执行、工具与诊断事件。
*   **事实层定义**：仅 `FACT_EVENT` 与 `OBSERVATION_EVENT` 可作为“事实输入”参与状态计算。
*   **治理层事件**：`PROPOSAL_EVENT`、`DECISION_EVENT`、`EXECUTION_EVENT`、`TOOL_*`、`AGENT_DIAGNOSTIC_EVENT` 属于治理/过程事件。
*   **强制属性**：不可变性、强类型 (Schema-first)、可校验、时间/因果有序。

### 4.2.1 统一事件信封 (Unified Envelope)
所有事件必须共享统一信封字段：
*   `schema_version`
*   `sequence_number`（由总线分配，单调递增，作为全序锚点）
*   `event_id`
*   `event_category`
*   `event_name`
*   `occurred_at`
*   `trace_id`
*   `causation_id`
*   `producer`
*   `subject`
*   `payload`

### 4.3 事件分类
1.  **FACT_EVENT (事实事件)**：观测到的状态改变（如：`OrderCreated`）。
2.  **PROPOSAL_EVENT (提议事件)**：Agent 建议采取的行动（如：`ProposedRefund`）。
3.  **DECISION_EVENT (决策事件)**：仲裁后产生的唯一合法指令（如：`RefundApproved`）。
4.  **EXECUTION_EVENT (执行事件)**：执行结果的反馈（如：`ExecutionFailed`）。
5.  **OBSERVATION_EVENT (观测事件)**：由受控工具调用得到的外部观测结果（如：`CustomerHistoryObserved`）。
6.  **TOOL_CALL_EVENT (工具调用事件)**：记录一次 MCP 工具调用请求（参数摘要、调用者、时间戳）。
7.  **TOOL_RESULT_EVENT (工具结果事件)**：记录一次 MCP 工具调用结果或失败（结果摘要、错误码、证据指针）。
8.  **AGENT_DIAGNOSTIC_EVENT (诊断事件，可选)**：记录 Agent Runtime 的健康状态与运行元数据，不包含推理链内容。

### 4.4 事实版本与时效性 (Fact Versioning & TTL)
事实不是永远新鲜。任何用于决策的事实都必须带上版本和时效边界：
*   **版本引用**：`PROPOSAL_EVENT` 必须声明其依赖的事实集合（`based_on_events`）及版本/序号。
*   **时效约束**：提议必须声明 `max_fact_age_ms`（可接受事实年龄上限）。
*   **执行前校验**：执行层在执行前必须检查依赖事实是否仍满足版本与 TTL；不满足时必须拒绝执行并产出显式事件（如 `DecisionRejectedEvent` / `ExecutionAbortedStaleFact`）。

### 4.5 事实可信度分级 (Trust Levels)
并非所有“上总线的信息”可信度相同。系统必须区分来源等级：
*   **Tier-1 / System Facts**：由受控系统直接产生的事实（数据库变更、执行回执），默认高可信。
*   **Tier-2 / Tool Observations**：由 MCP 工具调用得到的观测，要求附证据引用与结果哈希。
*   **Tier-3 / Agent Inference**：由 Agent 推断得到的信息，不能直接驱动执行，必须经过仲裁或二次验证。
*   **仲裁输入要求**：可信度等级必须作为仲裁权重维度之一。

### 4.6 证据链回环 (Evidence Loopback)
为避免“同证据不同解读”污染事实层，系统必须分离原始证据与解释结果：
*   **Raw Evidence 层**：`TOOL_RESULT_EVENT` 记录原始响应引用、摘要、哈希、来源工具与时间戳，作为可审计基线。
*   **Extracted Interpretation 层**：`OBSERVATION_EVENT` 仅记录结构化提取字段与置信度，并显式引用对应 `TOOL_RESULT_EVENT`。
*   **冲突处理**：若多个 Agent 对同一证据引用给出冲突提取，仲裁层必须触发 `NeedsHumanReview` 或交由专门校验 Agent 复核，不得静默择一。

### 4.7 事实生产契约 (Fact Production Contract)
`FACT_EVENT` 是系统中唯一可写入“已确认事实层 (Confirmed Facts)”的事件类别。Agent 不直接生产 `FACT_EVENT`。

#### 4.7.1 事实生产公理
*   **Agent 不是事实声明者**：Agent 是提议者和观测者，可间接引发事实产生，但无权直接声称现实状态已改变。
*   **事实必须来自可验证通道**：`FACT_EVENT` 只能由受控外部观测或总线内置确定性衍生逻辑产出。

#### 4.7.2 三条事实生产通道
1.  **外部观测通道**：`sensor / api / database_snapshot -> FACT_EVENT(observed_from=db/api/webhook)`。
2.  **执行反馈衍生通道**：`EXECUTION_EVENT -> FDR -> FACT_EVENT(observed_from=executor_feedback)`。
3.  **人工介入通道**：`Human Input -> Gateway(system) -> FACT_EVENT(observed_from=human_input)`。

#### 4.7.3 事实衍生反应器 (Fact Derivation Reactor, FDR)
FDR 是 Bus 基础设施的内在逻辑，不是独立 Agent。职责是将执行反馈确定性衍生为事实：
*   `EXECUTION_EVENT(status=success)` -> 产出执行成功事实
*   `EXECUTION_EVENT(status=failed)` -> 产出执行失败事实
*   `EXECUTION_EVENT(status=partial)` -> 产出部分成功事实并触发补偿流程
*   `EXECUTION_EVENT(status=timeout)` -> 产出超时事实

FDR 产出事件最小字段要求：
*   `producer = { type: "system", id: "fact-derivation-reactor", version: "<derivation_rule_version>" }`
*   `causation_id = <source_execution_event_id>`
*   `trace_id` 与源执行事件一致
*   `payload.decision_id` 与 `payload.execution_id` 必填，用于完整追溯 `Decision -> Execution -> Fact`
*   `payload.derivation_rule_id` 与 `payload.derivation_rule_version` 必填

### 4.8 观测与事实边界 (Observation vs Fact Boundary)
`OBSERVATION_EVENT` 不是 `FACT_EVENT`，不得自动提升为事实层：
*   **FACT_EVENT**：确定性事实声明，不携带 `confidence`。
*   **OBSERVATION_EVENT**：Agent 通过工具中介得到的观测，必须携带 `confidence` 与证据引用。
*   **仲裁策略**：若仅有 Tier-2 观测不足以支撑执行，仲裁层可拒绝并返回 `INSUFFICIENT_EVIDENCE_TIER`，随后系统应触发 Tier-1 事实采集流程。

### 4.9 Producer-Category 权限矩阵
为确保发布权限可审计、可校验，采用以下最小权限矩阵：
*   `sensor / api / database_snapshot` -> `FACT_EVENT`
*   `agent` -> `PROPOSAL_EVENT, OBSERVATION_EVENT, TOOL_CALL_EVENT, TOOL_RESULT_EVENT, AGENT_DIAGNOSTIC_EVENT`
*   `arbitrator` -> `DECISION_EVENT`
*   `executor` -> `EXECUTION_EVENT`
*   `system` -> `FACT_EVENT`（衍生/人工介入）, `AGENT_DIAGNOSTIC_EVENT`

---

## 5. Agent 模型 (Agent Model)

### 5.1 角色重定义
Agent 不是单个 LLM 进程，而是**“多角色协作的事件处理器 (Agent Matrix / Graph Runtime)”**。
*   **对外语义**：对外单 Agent（黑盒协作、白盒输出），系统只关心其产出的规范事件。
*   **对内语义**：对内多 Agent/多角色节点（LangChain/LangGraph 编排），通过显式状态机协作。
*   **输入**：订阅 Fact Bus 事件流（按窗口/触发规则）。
*   **输出**：仅允许发布 `PROPOSAL_EVENT`、`OBSERVATION_EVENT`、`AGENT_DIAGNOSTIC_EVENT`（可选）。
*   **约束**：Agent 不拥有执行权，严禁直接修改系统状态；任何现实世界副作用必须经过执行层。

### 5.2 多 Agent 编排形态 (LangChain / LangGraph)
Agent Matrix 的节点分为两类：
1.  **Reasoning Nodes（推理节点）**  
    仅做推理与结构化产出，不产生现实世界副作用。输出必须是 Schema 可校验的结构化对象。
2.  **Tool Nodes（工具节点，经由 MCP）**  
    通过 MCP 调用外部能力（检索、查询、分析），调用结果必须回写总线为 `OBSERVATION_EVENT` 或相关工具事件。

### 5.3 MCP 调用治理边界
为防止绕过治理平面，MCP 调用必须满足以下硬约束：
*   **工具调用即事件**：每次调用必须产出 `TOOL_CALL_EVENT` 与 `TOOL_RESULT_EVENT`，并记录 `tool_name`、`caller_role`、参数哈希、开始/结束时间、错误码、结果摘要、证据引用。
*   **结果可复算**：可复算结果必须记录查询条件与快照引用；不可复算结果至少记录原始响应摘要与内容哈希。
*   **禁止写副作用直连**：退款、下单、发消息等写操作不得由 Agent 直接调用，必须通过 `PROPOSAL_EVENT -> DECISION_EVENT -> EXECUTION_EVENT` 链路执行。

### 5.4 No P2P 在 Agent Matrix 下的解释
*   **系统边界外**：禁止跨模块私聊；所有跨模块协作必须经过 Bus。
*   **系统边界内**：允许 Matrix 内部角色通过 Graph State 通信，但该状态在审计需要时必须可导出为结构化审计产物（如状态迁移摘要事件）。
*   **禁止隐藏通道**：角色节点不得通过未受控网络/外部存储交换信息。

### 5.5 Agent Matrix 输出契约
为保证仲裁层确定性，Agent 输出必须收敛到可校验结构：
*   **ProposedAction**：`action_type, params, expected_outcome, cost, risk, required_facts, confidence`
*   **Observation**：`source_tool, query_ref, evidence_ref, freshness, confidence, extracted_fields`
*   **NeedMoreFacts**：必须事件化，不允许停留在内部信号。推荐映射为 `DECISION_EVENT(outcome=rejected, reason_code=NEED_MORE_FACTS)`，并附 `missing_fact_keys` 与 `retry_hint`。

### 5.6 拒绝后补充观测循环 (Rejection-Observation-Reproposal)
当 Agent 收到 `DECISION_EVENT(outcome=rejected)` 后，必须进入显式子循环：
1.  读取 `reason_code` 与 `retry_hint`，定位缺失事实键。
2.  触发 MCP 工具调用：产出 `TOOL_CALL_EVENT` / `TOOL_RESULT_EVENT`。
3.  发布新增 `OBSERVATION_EVENT`，并引用对应证据事件。
4.  重新发布 `PROPOSAL_EVENT`，其 `based_on_events` 必须包含新增观测事件。
5.  新提议 `causation_id` 应指向触发它的 rejected `DECISION_EVENT`。

同一 `trace_id` 连续拒绝超过阈值（如 3 次）时，系统应升级为 `NeedsHumanReview` 流程。

---

## 6. 仲裁层 (Arbitration Layer)

### 6.1 核心逻辑
仲裁层是系统熵的“压缩机”，将多个可能的提议压缩为唯一的决策。

### 6.2 约束条件
*   **非 LLM 化**：仲裁逻辑必须是确定性的代码或规则引擎。
*   **输入维度**：基于事件元数据、优先级 (Priority)、风险等级 (Risk)、预设策略 (Policy)、成本 (Cost)、证据质量 (Evidence Strength)、数据新鲜度 (Freshness)。
*   **输出**：产生唯一的 `DecisionEvent` 或显式的 `DecisionRejectedEvent`。

### 6.3 仲裁反馈闭环 (Arbitration Feedback Loop)
`DecisionRejectedEvent` 必须是可机器消费的结构化反馈，而非自然语言解释：
*   **最小字段**：`reason_code`、`policy_id`、`policy_version`、`conflict_with_proposal_ids`、`retry_hint`。
*   **retry_hint 结构**：应至少包含 `missing_fact_keys`、`required_trust_tier`、`preferred_sources`、`max_observation_age_ms`。
*   **可学习性**：Agent 必须据此调整下一轮提议，避免重复无效提议。
*   **防死循环**：同一 `trace_id` 下连续拒绝超过阈值时，系统必须产出升级事件（如 `NeedsHumanReview`）。

### 6.4 预检与激活策略集 (Preflight & Active Policy Set)
为避免 Agent 在策略边界上盲目试错，仲裁层应支持预检语义：
*   **Arbitration Dry-run**：在不生成最终决策的前提下返回“若提交将触发哪些策略”。
*   **激活策略回传**：`DecisionRejectedEvent` 除拒绝原因外，应返回当前激活策略集合（`active_policy_ids`）与命中条件摘要。
*   **可终止性**：当系统判断“当前上下文不存在可接受提议”时，必须返回终止信号（如 `NoFeasibleProposal`），禁止无限重试。

---

## 7. 决策与执行分离 (Decision vs Execution)

**决策 (Decision) ≠ 执行 (Execution)**
*   **决策**：代表系统的意图，是逻辑上的终点。
*   **执行**：是与现实世界的交互，允许失败、超时或部分成功。
*   **闭环**：执行结果必须作为新的 `FACT_EVENT` 重新进入总线，触发下一轮循环。

### 7.1 补偿与部分成功 (Compensation / Saga)
执行失败不是终点，必须有补偿策略：
*   **状态分类**：`EXECUTION_EVENT` 至少支持 `success / failed / partial / timeout`。
*   **部分成功处理**：`partial` 必须触发补偿提议或补偿决策，不允许长期停留在中间态。
*   **策略化重试**：重试次数、退避策略、人工接管阈值必须由策略层显式配置，不得隐式重试。

### 7.2 执行反馈事实衍生 (Execution-to-Fact Derivation)
执行结果进入事实层采用两阶段流程，禁止隐式“直接变事实”：
*   **阶段一：执行报告**  
    执行层发布 `EXECUTION_EVENT`，仅描述“执行尝试与结果”，不直接声明已确认事实。
*   **阶段二：事实衍生**  
    FDR 订阅 `EXECUTION_EVENT`，以确定性规则 `f(EXECUTION_EVENT, DECISION_EVENT) -> FACT_EVENT` 产出事实。

衍生规则约束：
*   必须是确定性纯函数，禁止调用外部系统。
*   必须版本化并在事件中落地 `derivation_rule_version`。
*   不得引入执行事件与其关联决策之外的额外输入。
*   `failed/partial/timeout` 同样必须衍生事实，禁止形成“事实空白区”。

---

## 8. 可回放性 (Replay & Auditability)

**硬性指标：**
如果移除所有 Agent，仅通过回放 `Event Log` 无法 100% 复现历史决策过程，则该系统不符合本工程规范。

*   **意义**：这相当于飞机的“黑匣子”或 CAN 总线的记录仪，是调试、审计、合规和责任归因的基础。
*   **MCP 可回放要求**：工具调用链必须可重建，至少包含 `TOOL_CALL_EVENT` + `TOOL_RESULT_EVENT` + 证据引用；仲裁重放必须绑定 `Policy Version` 与工具结果哈希。
*   **Agent Runtime 可回放要求**：关键状态迁移必须可导出为结构化事件（例如 `graph_node`、`state_version`、`input_event_ids`、`output_event_ids`、`transition_reason_code`），无需暴露完整推理链文本。

---

## 9. 失败模型 (Failure Model)

失败在系统中是“一等公民”。
*   冲突的提议、缺失的事实、过时的事件、执行失败、策略违规，都必须被显式记录并转化为事件。
*   **严禁静默失败。**

### 9.1 冷启动与触发 (Cold Start & Triggering)
系统必须定义第一推动力，避免“全部组件等待事件”的死锁：
*   **Entrypoint/Gateway**：外部请求必须先转化为初始事实事件（如 `UserIntentDetected` / `ExternalSignalReceived`）。
*   **触发规则**：Agent Matrix 的触发条件（按事件类型、窗口、优先级）必须可配置、可审计。
*   **无输入无推理**：禁止无事实输入的自发提议。

---

## 10. 状态投影 (State Projection)

事件日志是历史真相，不等于在线读取模型。系统必须提供确定性的只读状态投影：
*   **Projection 职责**：将 Event Log 压缩为当前状态快照，供 Agent 和仲裁层低延迟读取。
*   **一致性原则**：Projection 只能由事件驱动更新，不得绕过总线直接写入。
*   **可追溯性**：每个投影状态必须可追溯到其来源事件集合和版本。
*   **衍生版本绑定**：涉及执行反馈衍生的投影更新，必须绑定事件内的 `derivation_rule_version`，禁止用“当前最新规则”重解释历史事件。
*   **上下文预算控制**：Agent 默认读取投影 + 增量事件，而非全量回放历史事件。

### 10.1 在途决策视图 (Pending Decisions View)
为避免并发场景下的“幻影读”，Projection 必须包含在途决策信息：
*   **投影组成**：`Confirmed Facts + Pending Decisions + Pending Executions`。
*   **可见性规则**：任何已通过仲裁但未完成执行的决策，都必须在投影层可见并可被 Agent 感知。
*   **并发防护**：Agent 发布提议时应携带读取投影版本（`projection_version`）；版本失配时触发重评估，而非直接沿用旧提议。

---

## 11. MVP 闭环门槛 (MVP Closure Gates)

MVP 上线前至少满足以下五项硬门槛：
1.  **Fact Versioning**：提议与执行链路具备事实版本和 TTL 校验能力。
2.  **Arbitration Feedback**：拒绝决策具备结构化原因码与重试提示。
3.  **Entrypoint Triggering**：外部请求到初始事实事件的网关路径明确可测。
4.  **State Projection**：存在可追溯、可审计的在线状态投影供 Agent 消费。
5.  **Execution-to-Fact Derivation**：`EXECUTION_EVENT -> FDR -> FACT_EVENT` 链路可测、可回放、可版本化。

---

## 12. 总结 (Guiding Principle)

> **Agent 制造可能性。**
> **事件定义现实。**
> **仲裁强制秩序。**

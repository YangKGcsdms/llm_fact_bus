# Event Contract v0

This document defines the machine-validated event contract for the Fact Bus MVP.

## 1) Unified Event Envelope

Every event on the bus must follow this envelope:

- `schema_version`: semver for event schema (e.g. `1.0.0`)
- `sequence_number`: server-assigned monotonic number (global ordering anchor)
- `event_id`: globally unique id (UUID)
- `event_category`: one of:
  - `FACT_EVENT`
  - `PROPOSAL_EVENT`
  - `DECISION_EVENT`
  - `EXECUTION_EVENT`
  - `OBSERVATION_EVENT`
  - `TOOL_CALL_EVENT`
  - `TOOL_RESULT_EVENT`
  - `AGENT_DIAGNOSTIC_EVENT`
- `event_name`: domain event name
- `occurred_at`: RFC3339 timestamp in UTC
- `trace_id`: groups one business flow
- `causation_id`: direct parent event id (nullable)
- `producer`:
  - `type`: `sensor | api | database_snapshot | agent | arbitrator | executor | system`
  - `id`: stable producer identity
  - `version`: producer version string
- `subject`:
  - `type`: business type, e.g. `order`, `ticket`
  - `id`: business id
- `payload`: category-specific body

## 2) Category Payload Rules

### 2.1 FACT_EVENT

- Required: `facts` (object), `observed_from` (`db | api | webhook | executor_feedback | human_input | system`)
- Conditional (when `observed_from = executor_feedback`): `derivation_rule_id`, `derivation_rule_version`, `decision_id`, `execution_id` — all required

### 2.2 PROPOSAL_EVENT

- Required: `proposal_id`, `proposed_action` (object), `based_on_events` (non-empty UUID list), `risk_level`, `cost_estimate`, `priority`, `max_fact_age_ms`
- Optional but recommended: `projection_version`
- Integrity rule: every id in `based_on_events` must already exist in the event store.

### 2.3 DECISION_EVENT

- Required: `decision_id`, `decision_on_proposals`, `outcome`, `policy_id`, `policy_version`, `reason_code`
- Conditional (when `outcome = rejected`): `retry_hint` (structured object) — required
- `retry_hint` structure: `{ missing_fact_keys: string[], required_trust_tier: string, preferred_sources: string[], max_observation_age_ms: integer }`
- Optional rejection fields: `conflict_with_proposal_ids`, `active_policy_ids`

### 2.4 EXECUTION_EVENT

- Required: `decision_event_id`, `execution_id`, `status`, `executor`
- Optional: `external_reference`, `error_code`, `error_message`

### 2.5 OBSERVATION_EVENT

- Required: `source_tool`, `evidence_ref`, `confidence`
- Optional: `extracted_fields`, `freshness`

### 2.6 TOOL_CALL_EVENT

- Required: `tool_name`, `caller_role`, `args_hash`, `started_at`

### 2.7 TOOL_RESULT_EVENT

- Required: `tool_name`, `status` (`success | failed`), `result_hash`
- Optional: `evidence_ref`, `error_code`, `error_message`

### 2.8 AGENT_DIAGNOSTIC_EVENT

- Required: `diagnostic_type`, `state_version`
- Optional: `graph_node`, `input_event_ids`, `output_event_ids`, `transition_reason_code`

## 3) Fact Production Contract (Aligned with DESIGN)

`FACT_EVENT` is the only event category that can write to the confirmed-facts layer.

- Agent never publishes `FACT_EVENT` directly.
- Agent can only publish proposal/observation/tool/diagnostic categories.
- New facts are produced through controlled channels:
  1. external observation (`sensor/api/database_snapshot`)
  2. execution feedback derivation (`FDR`, producer type `system`)
  3. human-input gateway (`system`)

### 3.1 Fact Derivation Reactor (FDR)

FDR is part of bus infrastructure, not an agent.

- Input: `EXECUTION_EVENT` (+ related `DECISION_EVENT` by reference)
- Function: deterministic derivation only  
  `f(EXECUTION_EVENT, DECISION_EVENT) -> FACT_EVENT`
- Output must include:
  - `producer.type = "system"`
  - `producer.id = "fact-derivation-reactor"`
  - `observed_from = "executor_feedback"`
  - `causation_id = <execution_event_id>`
  - `payload.derivation_rule_id`
  - `payload.derivation_rule_version`

### 3.2 Observation vs Fact Boundary

- `OBSERVATION_EVENT` is **not** `FACT_EVENT`.
- `OBSERVATION_EVENT` requires `confidence` and `evidence_ref`.
- If evidence tier is insufficient, arbitration may reject with:
  - `reason_code = INSUFFICIENT_EVIDENCE_TIER`
- Observation cannot be auto-promoted to fact without controlled path.

### 3.3 Producer-Category Permission Matrix

- `sensor | api | database_snapshot` -> `FACT_EVENT`
- `agent` -> `PROPOSAL_EVENT | OBSERVATION_EVENT | TOOL_CALL_EVENT | TOOL_RESULT_EVENT | AGENT_DIAGNOSTIC_EVENT`
- `arbitrator` -> `DECISION_EVENT`
- `executor` -> `EXECUTION_EVENT`
- `system` -> `FACT_EVENT` (derivation/human input), `AGENT_DIAGNOSTIC_EVENT`

## 4) Causality and Replay Rules

- No event without `trace_id`.
- `PROPOSAL_EVENT` must include `based_on_events` and references must be resolvable.
- `EXECUTION_EVENT` must include `decision_event_id`.
- Replay uses `sequence_number` as source-of-truth ordering.
- Failures must be emitted as events; silent failures are forbidden.
- Rejection loop must be eventized:
  - `DECISION_EVENT(rejected)` -> `TOOL_CALL_EVENT` -> `TOOL_RESULT_EVENT` -> `OBSERVATION_EVENT` -> new `PROPOSAL_EVENT`

## 5) Execution-to-Fact Derivation Rules (MVP)

- `EXECUTION_EVENT(status=success)` -> derived success `FACT_EVENT`
- `EXECUTION_EVENT(status=failed)` -> derived failure `FACT_EVENT`
- `EXECUTION_EVENT(status=partial)` -> derived partial-state `FACT_EVENT` + compensation trigger
- `EXECUTION_EVENT(status=timeout)` -> derived timeout `FACT_EVENT`

## 6) Minimal Example (PROPOSAL_EVENT)

```json
{
  "schema_version": "1.0.0",
  "event_id": "dc31a700-6436-4ad4-a137-74831241a2f6",
  "event_category": "PROPOSAL_EVENT",
  "event_name": "ProposedRefund",
  "occurred_at": "2026-02-13T09:00:05Z",
  "trace_id": "trace-ord-1001",
  "causation_id": "b6cdb0f0-6f7f-4df0-b2bc-ec2f4f4d5c6a",
  "producer": { "type": "agent", "id": "refund-agent", "version": "model-2026-02" },
  "subject": { "type": "order", "id": "ORD-1001" },
  "payload": {
    "proposal_id": "prp-2001",
    "proposed_action": { "type": "refund", "amount": 1200, "currency": "USD" },
    "based_on_events": ["b6cdb0f0-6f7f-4df0-b2bc-ec2f4f4d5c6a"],
    "risk_level": "medium",
    "cost_estimate": 1200,
    "priority": 80,
    "max_fact_age_ms": 60000,
    "projection_version": 42
  }
}
```

## 7) Minimal Example (Execution -> Derived Fact)

```json
{
  "schema_version": "1.0.0",
  "event_id": "90d1f46b-947e-4ca0-91d2-65e00b777ec6",
  "event_category": "EXECUTION_EVENT",
  "event_name": "RefundExecutionSucceeded",
  "occurred_at": "2026-02-13T09:00:08Z",
  "trace_id": "trace-ord-1001",
  "causation_id": "ab9b2ef8-2f69-4170-a170-9dcdf9391f2e",
  "producer": { "type": "executor", "id": "payments-gateway", "version": "3.2.1" },
  "subject": { "type": "order", "id": "ORD-1001" },
  "payload": {
    "decision_event_id": "ab9b2ef8-2f69-4170-a170-9dcdf9391f2e",
    "execution_id": "exe-9001",
    "status": "success",
    "executor": "payments-gateway",
    "external_reference": "psp_tx_8830"
  }
}
```

```json
{
  "schema_version": "1.0.0",
  "event_id": "5f4557ee-4d79-40da-8b70-5d8d0db4c4c8",
  "event_category": "FACT_EVENT",
  "event_name": "OrderRefunded",
  "occurred_at": "2026-02-13T09:00:09Z",
  "trace_id": "trace-ord-1001",
  "causation_id": "90d1f46b-947e-4ca0-91d2-65e00b777ec6",
  "producer": { "type": "system", "id": "fact-derivation-reactor", "version": "refund-success-v1" },
  "subject": { "type": "order", "id": "ORD-1001" },
  "payload": {
    "facts": {
      "order_id": "ORD-1001",
      "refund_amount": 1200,
      "refund_tx_ref": "psp_tx_8830"
    },
    "observed_from": "executor_feedback",
    "derivation_rule_id": "refund-success-to-fact",
    "derivation_rule_version": "v1",
    "decision_id": "dec-5001",
    "execution_id": "exe-9001"
  }
}
```

---

## 8) MVP Field Dictionary (v0.1 frozen)

This section enumerates every field, its requirement level, and condition.
Levels: **R** = required, **CR** = conditionally required, **O** = optional, **S** = server-assigned.

### 8.1 Unified Envelope

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `schema_version` | string (semver) | R | e.g. `1.0.0` |
| `sequence_number` | integer | S | Bus-assigned, monotonic, global ordering anchor |
| `event_id` | string (UUID) | R | Client-generated, globally unique |
| `event_category` | enum | R | One of the 8 categories |
| `event_name` | string | R | Domain event name |
| `occurred_at` | string (RFC3339 UTC) | R | When the event happened |
| `trace_id` | string | R | Groups one business flow |
| `causation_id` | string (UUID) / null | O | Direct parent event id |
| `producer` | object | R | See below |
| `subject` | object | R | See below |
| `payload` | object | R | Category-specific body |

### 8.2 Producer

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `type` | enum | R | `sensor \| api \| database_snapshot \| agent \| arbitrator \| executor \| system` |
| `id` | string | R | Stable producer identity |
| `version` | string | R | Producer version |

### 8.3 Subject

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `type` | string | R | Business entity type (e.g. `order`) |
| `id` | string | R | Business entity id |

### 8.4 FACT_EVENT Payload

| Field | Type | Level | Condition | Notes |
|-------|------|-------|-----------|-------|
| `facts` | object | R | — | Observed state |
| `observed_from` | enum | R | — | `db \| api \| webhook \| executor_feedback \| human_input \| system` |
| `derivation_rule_id` | string | CR | `observed_from = executor_feedback` | FDR rule identifier |
| `derivation_rule_version` | string | CR | `observed_from = executor_feedback` | FDR rule version |
| `decision_id` | string | CR | `observed_from = executor_feedback` | Traced decision |
| `execution_id` | string | CR | `observed_from = executor_feedback` | Traced execution |

### 8.5 PROPOSAL_EVENT Payload

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `proposal_id` | string | R | Unique proposal identifier |
| `proposed_action` | object | R | Structured action description |
| `based_on_events` | UUID[] (min 1) | R | Facts this proposal depends on |
| `risk_level` | enum | R | `low \| medium \| high \| critical` |
| `cost_estimate` | number | R | Estimated cost |
| `priority` | integer | R | Priority weight |
| `max_fact_age_ms` | integer (>=1) | R | Max acceptable age of referenced facts |
| `projection_version` | integer (>=1) | O | Projection snapshot version at read time |

### 8.6 DECISION_EVENT Payload

| Field | Type | Level | Condition | Notes |
|-------|------|-------|-----------|-------|
| `decision_id` | string | R | — | Unique decision identifier |
| `decision_on_proposals` | string[] (min 1) | R | — | Proposal ids being decided |
| `outcome` | enum | R | — | `approved \| rejected` |
| `policy_id` | string | R | — | Policy that produced the decision |
| `policy_version` | string | R | — | Version of that policy |
| `reason_code` | string | R | — | Machine-readable reason code |
| `retry_hint` | object | CR | `outcome = rejected` | Structured guidance for retry |
| `retry_hint.missing_fact_keys` | string[] | O | within `retry_hint` | Keys the proposer should observe |
| `retry_hint.required_trust_tier` | enum | O | within `retry_hint` | `tier_1 \| tier_2 \| tier_3` |
| `retry_hint.preferred_sources` | string[] | O | within `retry_hint` | Suggested observation sources |
| `retry_hint.max_observation_age_ms` | integer | O | within `retry_hint` | Freshness requirement |
| `conflict_with_proposal_ids` | string[] | O | — | Conflicting proposals |
| `active_policy_ids` | string[] | O | — | Currently active policies |

### 8.7 EXECUTION_EVENT Payload

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `decision_event_id` | string (UUID) | R | The decision event that authorized execution |
| `execution_id` | string | R | Unique execution identifier |
| `status` | enum | R | `success \| failed \| partial \| timeout` |
| `executor` | string | R | Executor identity |
| `external_reference` | string | O | External system reference |
| `error_code` | string | O | Error code on failure |
| `error_message` | string | O | Human-readable error message |

### 8.8 OBSERVATION_EVENT Payload

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `source_tool` | string | R | Tool that produced the observation |
| `evidence_ref` | string | R | Reference to raw evidence (TOOL_RESULT_EVENT) |
| `confidence` | number [0,1] | R | Observation confidence |
| `extracted_fields` | object | O | Structured extraction |
| `freshness` | string | O | Freshness indicator |

### 8.9 TOOL_CALL_EVENT Payload

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `tool_name` | string | R | MCP tool name |
| `caller_role` | string | R | Calling agent role |
| `args_hash` | string | R | Hash of call arguments |
| `started_at` | string (RFC3339) | R | Call start time |

### 8.10 TOOL_RESULT_EVENT Payload

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `tool_name` | string | R | MCP tool name |
| `status` | enum | R | `success \| failed` |
| `result_hash` | string | R | Hash of result content |
| `evidence_ref` | string | O | Evidence pointer |
| `error_code` | string | O | Error code on failure |
| `error_message` | string | O | Error message on failure |

### 8.11 AGENT_DIAGNOSTIC_EVENT Payload

| Field | Type | Level | Notes |
|-------|------|-------|-------|
| `diagnostic_type` | string | R | Diagnostic category |
| `state_version` | string | R | Runtime state version |
| `graph_node` | string | O | Current graph node |
| `input_event_ids` | UUID[] | O | Input event references |
| `output_event_ids` | UUID[] | O | Output event references |
| `transition_reason_code` | string | O | Reason for state transition |

### 8.12 Producer-Category Permission Matrix (Enforced)

| Producer Type | Allowed Categories |
|---------------|-------------------|
| `sensor` | `FACT_EVENT` |
| `api` | `FACT_EVENT` |
| `database_snapshot` | `FACT_EVENT` |
| `agent` | `PROPOSAL_EVENT`, `OBSERVATION_EVENT`, `TOOL_CALL_EVENT`, `TOOL_RESULT_EVENT`, `AGENT_DIAGNOSTIC_EVENT` |
| `arbitrator` | `DECISION_EVENT` |
| `executor` | `EXECUTION_EVENT` |
| `system` | `FACT_EVENT`, `AGENT_DIAGNOSTIC_EVENT` |

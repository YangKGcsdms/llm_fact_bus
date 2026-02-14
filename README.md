# Fact Bus

[English](README.md) | [简体中文](README-zh.md)

Fact Bus is an event-driven governance runtime for LLM/agent systems.
It enforces deterministic system behavior over probabilistic agents through:

- Contract-first events
- Deterministic arbitration
- Execution-to-fact derivation (FDR)
- Replayable event log and projection model

## Status

- Current stage: **MVP**
- Runtime: **Spring Boot (Java 17)**
- Storage: **in-memory event store** (prototype mode)

## Core Flow

```text
Gateway -> FACT_EVENT
Agent -> PROPOSAL_EVENT / OBSERVATION_EVENT / TOOL_* / DIAGNOSTIC
Arbitrator -> DECISION_EVENT
Executor -> EXECUTION_EVENT
FDR (inside bus) -> derived FACT_EVENT
Projection -> confirmed_facts + pending_decisions + pending_executions
```

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/events` | Publish contract-gated events |
| `GET` | `/v1/events` | Query events by trace/category/subject |
| `GET` | `/v1/events/stream` | SSE subscription |
| `POST` | `/v1/gateway/intents` | Cold-start request to FACT_EVENT |
| `GET` | `/v1/projections/{subjectType}/{subjectId}` | Read per-subject projection |

## MVP Capabilities

- 8 event categories with schema-first validation
- Producer-category permission matrix validation
- Duplicate `event_id` rejection (HTTP 409)
- Deterministic arbitration with structured rejection feedback
- FDR pipeline: `EXECUTION_EVENT -> FACT_EVENT`
- Projection view with pending state visibility
- Unified machine-readable error response
- Monotonic `sequence_number` assignment

## Quick Start

### 1) Run service

```bash
mvn spring-boot:run
```

### 2) Health check

```bash
curl http://localhost:8080/actuator/health
```

### 3) Inject initial intent

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

### 4) Publish proposal

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

### 5) Query projection

```bash
curl http://localhost:8080/v1/projections/order/ORD-1001
```

## Testing

Current test suites include:

- contract validation tests
- integration happy path
- rejection and re-proposal path
- replay consistency checks

Run locally:

```bash
mvn test
```

## Error Format

All API errors follow:

```json
{
  "error_code": "CONTRACT_VIOLATION",
  "message": "payload.max_fact_age_ms is required",
  "timestamp": "2026-02-14T09:00:00Z"
}
```

Common codes:
`CONTRACT_VIOLATION`, `DUPLICATE_EVENT`, `BAD_REQUEST`, `INVALID_ARGUMENT`, `INTERNAL_ERROR`.

## Repository Structure

```text
src/main/java/com/factbus/
  api/           # REST controllers and API error handling
  bus/           # event bus and store
  contract/      # envelope, enums, validator
  arbitration/   # deterministic arbitration
  fdr/           # Fact Derivation Reactor
  projection/    # state projection model and services
```

## Design & Contracts

- `DESIGN.md`: governance architecture and principles
- `EVENT_CONTRACT_V0.md`: contract spec and field dictionary
- `event-contract-v0.schema.json`: machine-checkable schema
- `LANGCHAIN_INTEGRATION.md`: Python/LangChain integration guide

## .gitignore Policy

Root `.gitignore` excludes:

- build artifacts (`target/`, `build/`, `out/`)
- IDE/project-local files (`.idea/`, `.vscode/`, `*.iml`)
- OS artifacts (`.DS_Store`, `Thumbs.db`)
- local environment/secrets (`.env*`, `secrets/`)
- logs and local reports

This keeps the repo reproducible and prevents accidental leakage of local or sensitive data.

## Contributing

Contributions are welcome for non-commercial collaboration:

1. Open an issue describing the problem/approach.
2. Keep changes aligned with `DESIGN.md` and contract files.
3. Add or update tests for behavior changes.
4. Submit focused PRs with clear rationale.

## Security Notes

- This MVP has no production-grade authN/authZ yet.
- Do not expose directly to untrusted public traffic.
- Do not commit secrets or production credentials.

## License (Non-Commercial)

This project is licensed under **CC BY-NC 4.0** (non-commercial).

- See `LICENSE` for details.
- Commercial use requires a separate commercial license from the maintainer.

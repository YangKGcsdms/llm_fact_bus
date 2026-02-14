# LangChain Integration Under Fact Bus Constraints

This guide explains how Python LangChain agents should work with Fact Bus governance.

## Core rule

Agent is a proposer only:

- Read facts from bus
- Reason internally
- Publish `PROPOSAL_EVENT`
- Wait for `DECISION_EVENT`
- Never execute side effects directly

## Integration pattern

Use LangChain tools as bus adapters, not direct business executors:

1. `read_facts(trace_id)` -> `GET /v1/events`
2. `publish_proposal(proposal_payload)` -> `POST /v1/events` (`PROPOSAL_EVENT`)
3. `wait_decision(trace_id)` -> `GET /v1/events/stream?traceId=...&eventCategory=DECISION_EVENT`

This keeps reasoning in Python and governance in Bus.

## Minimal Python example

```python
import uuid
import datetime as dt
import requests
from langchain.tools import tool

BUS_URL = "http://localhost:8080"


@tool
def read_facts(trace_id: str) -> list:
    """Fetch FACT_EVENTs for one trace."""
    r = requests.get(
        f"{BUS_URL}/v1/events",
        params={"traceId": trace_id, "eventCategory": "FACT_EVENT", "limit": 200},
        timeout=10,
    )
    r.raise_for_status()
    return r.json()


@tool
def publish_proposal(trace_id: str, subject_type: str, subject_id: str, action: dict, based_on_events: list[str]) -> dict:
    """Publish one PROPOSAL_EVENT to Fact Bus."""
    event = {
        "schema_version": "1.0.0",
        "event_id": str(uuid.uuid4()),
        "event_category": "PROPOSAL_EVENT",
        "event_name": "ProposedAction",
        "occurred_at": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "trace_id": trace_id,
        "causation_id": based_on_events[-1] if based_on_events else None,
        "producer": {"type": "agent", "id": "lc-agent", "version": "v0"},
        "subject": {"type": subject_type, "id": subject_id},
        "payload": {
            "proposal_id": f"prp-{uuid.uuid4()}",
            "proposed_action": action,
            "based_on_events": based_on_events,
            "risk_level": "medium",
            "cost_estimate": 1,
            "priority": 50,
        },
    }
    r = requests.post(f"{BUS_URL}/v1/events", json=event, timeout=10)
    r.raise_for_status()
    return r.json()
```

## Recommended LangChain graph shape

- Node A: fetch facts from bus
- Node B: LLM reasoning (internal only)
- Node C: publish proposal to bus
- Node D: wait for decision event
- Node E: if rejected, explain and optionally propose alternative

## Guardrails to add in Python

- Hard block any tool that does direct external writes (payments/refunds/ticket close).
- Validate outgoing event payload shape before `POST`.
- Include `trace_id` in every tool call and log line.

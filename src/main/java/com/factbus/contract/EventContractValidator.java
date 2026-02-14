package com.factbus.contract;

import com.factbus.bus.EventStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class EventContractValidator {

    private static final Pattern SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");

    public void validate(EventEnvelope event, EventStore eventStore) {
        requireNonNull(event, "event cannot be null");
        requireNonNull(eventStore, "event_store is required");
        requireString(event.getSchemaVersion(), "schema_version is required");
        if (!SEMVER.matcher(event.getSchemaVersion()).matches()) {
            throw new ContractViolationException("schema_version must be semver like 1.0.0");
        }

        requireUuid(event.getEventId(), "event_id must be a valid UUID");
        requireNonNull(event.getEventCategory(), "event_category is required");
        requireString(event.getEventName(), "event_name is required");
        requireNonNull(event.getOccurredAt(), "occurred_at is required");
        requireString(event.getTraceId(), "trace_id is required");

        if (event.getCausationId() != null && !event.getCausationId().isBlank()) {
            requireUuid(event.getCausationId(), "causation_id must be a valid UUID when provided");
        }

        validateProducer(event.getProducer(), event.getEventCategory());
        validateSubject(event.getSubject());

        if (event.getPayload() == null) {
            throw new ContractViolationException("payload is required");
        }

        switch (event.getEventCategory()) {
            case FACT_EVENT -> validateFactPayload(event.getPayload());
            case PROPOSAL_EVENT -> validateProposalPayload(event.getPayload(), eventStore);
            case DECISION_EVENT -> validateDecisionPayload(event.getPayload());
            case EXECUTION_EVENT -> validateExecutionPayload(event.getPayload());
            case OBSERVATION_EVENT -> validateObservationPayload(event.getPayload());
            case TOOL_CALL_EVENT -> validateToolCallPayload(event.getPayload());
            case TOOL_RESULT_EVENT -> validateToolResultPayload(event.getPayload());
            case AGENT_DIAGNOSTIC_EVENT -> validateDiagnosticPayload(event.getPayload());
        }
    }

    private void validateProducer(EventEnvelope.Producer producer, EventCategory category) {
        requireNonNull(producer, "producer is required");
        requireNonNull(producer.getType(), "producer.type is required");
        requireString(producer.getId(), "producer.id is required");
        requireString(producer.getVersion(), "producer.version is required");

        // Full producer-category permission matrix (DESIGN.md §4.9)
        switch (producer.getType()) {
            case SENSOR, API, DATABASE_SNAPSHOT -> {
                if (category != EventCategory.FACT_EVENT) {
                    throw new ContractViolationException(
                        producer.getType().getValue() + " producer is only allowed to publish FACT_EVENT");
                }
            }
            case AGENT -> {
                if (!List.of(
                        EventCategory.PROPOSAL_EVENT,
                        EventCategory.OBSERVATION_EVENT,
                        EventCategory.TOOL_CALL_EVENT,
                        EventCategory.TOOL_RESULT_EVENT,
                        EventCategory.AGENT_DIAGNOSTIC_EVENT
                    ).contains(category)) {
                    throw new ContractViolationException(
                        "agent producer is only allowed to publish PROPOSAL/OBSERVATION/TOOL/DIAGNOSTIC events");
                }
            }
            case ARBITRATOR -> {
                if (category != EventCategory.DECISION_EVENT) {
                    throw new ContractViolationException(
                        "arbitrator producer is only allowed to publish DECISION_EVENT");
                }
            }
            case EXECUTOR -> {
                if (category != EventCategory.EXECUTION_EVENT) {
                    throw new ContractViolationException(
                        "executor producer is only allowed to publish EXECUTION_EVENT");
                }
            }
            case SYSTEM -> {
                if (!List.of(EventCategory.FACT_EVENT, EventCategory.AGENT_DIAGNOSTIC_EVENT).contains(category)) {
                    throw new ContractViolationException(
                        "system producer is only allowed to publish FACT_EVENT or AGENT_DIAGNOSTIC_EVENT");
                }
            }
        }
    }

    private void validateSubject(EventEnvelope.Subject subject) {
        requireNonNull(subject, "subject is required");
        requireString(subject.getType(), "subject.type is required");
        requireString(subject.getId(), "subject.id is required");
    }

    private void validateFactPayload(Map<String, Object> payload) {
        requireObject(payload.get("facts"), "payload.facts must be an object");
        String observedFrom = requireString(payload.get("observed_from"), "payload.observed_from is required");
        if (!List.of("db", "api", "webhook", "executor_feedback", "human_input", "system").contains(observedFrom)) {
            throw new ContractViolationException("payload.observed_from is invalid");
        }

        // FDR derivation fields are required when observed_from=executor_feedback (DESIGN.md §4.7.3)
        if ("executor_feedback".equals(observedFrom)) {
            requireString(payload.get("derivation_rule_id"),
                "payload.derivation_rule_id is required when observed_from=executor_feedback");
            requireString(payload.get("derivation_rule_version"),
                "payload.derivation_rule_version is required when observed_from=executor_feedback");
            requireString(payload.get("decision_id"),
                "payload.decision_id is required when observed_from=executor_feedback");
            requireString(payload.get("execution_id"),
                "payload.execution_id is required when observed_from=executor_feedback");
        }
    }

    private void validateProposalPayload(Map<String, Object> payload, EventStore eventStore) {
        requireString(payload.get("proposal_id"), "payload.proposal_id is required");
        requireObject(payload.get("proposed_action"), "payload.proposed_action must be an object");
        List<?> basedOnEvents = requireList(payload.get("based_on_events"), "payload.based_on_events is required");
        if (basedOnEvents.isEmpty()) {
            throw new ContractViolationException("payload.based_on_events must contain at least 1 event id");
        }
        for (Object id : basedOnEvents) {
            String factEventId = Objects.toString(id, null);
            requireUuid(factEventId, "payload.based_on_events must contain valid UUIDs");
            if (!eventStore.existsByEventId(factEventId)) {
                throw new ContractViolationException("payload.based_on_events contains unknown event_id: " + factEventId);
            }
        }
        String riskLevel = requireString(payload.get("risk_level"), "payload.risk_level is required");
        if (!List.of("low", "medium", "high", "critical").contains(riskLevel)) {
            throw new ContractViolationException("payload.risk_level is invalid");
        }
        requireNumber(payload.get("cost_estimate"), "payload.cost_estimate is required");
        requireInteger(payload.get("priority"), "payload.priority is required");

        // max_fact_age_ms is required for MVP (DESIGN.md §4.4)
        Object maxFactAge = payload.get("max_fact_age_ms");
        requireInteger(maxFactAge, "payload.max_fact_age_ms is required");
        if (maxFactAge instanceof Number num && num.longValue() < 1) {
            throw new ContractViolationException("payload.max_fact_age_ms must be >= 1");
        }
    }

    private void validateDecisionPayload(Map<String, Object> payload) {
        requireString(payload.get("decision_id"), "payload.decision_id is required");
        List<?> proposals = requireList(payload.get("decision_on_proposals"), "payload.decision_on_proposals is required");
        if (proposals.isEmpty()) {
            throw new ContractViolationException("payload.decision_on_proposals must contain at least 1 proposal id");
        }
        String outcome = requireString(payload.get("outcome"), "payload.outcome is required");
        if (!List.of("approved", "rejected").contains(outcome)) {
            throw new ContractViolationException("payload.outcome is invalid");
        }
        requireString(payload.get("policy_id"), "payload.policy_id is required");
        requireString(payload.get("policy_version"), "payload.policy_version is required");
        requireString(payload.get("reason_code"), "payload.reason_code is required");

        // Structured retry_hint is required when outcome=rejected (DESIGN.md §6.3)
        if ("rejected".equals(outcome)) {
            Object retryHintRaw = payload.get("retry_hint");
            if (!(retryHintRaw instanceof Map<?, ?> retryHint)) {
                throw new ContractViolationException(
                    "payload.retry_hint must be a structured object when outcome=rejected");
            }
            // retry_hint internal fields are optional but the object itself must be present
            // Validate known fields if present
            if (retryHint.containsKey("required_trust_tier")) {
                String tier = Objects.toString(retryHint.get("required_trust_tier"), "");
                if (!List.of("tier_1", "tier_2", "tier_3").contains(tier)) {
                    throw new ContractViolationException(
                        "payload.retry_hint.required_trust_tier must be tier_1, tier_2, or tier_3");
                }
            }
            if (retryHint.containsKey("missing_fact_keys")) {
                if (!(retryHint.get("missing_fact_keys") instanceof List<?>)) {
                    throw new ContractViolationException(
                        "payload.retry_hint.missing_fact_keys must be an array");
                }
            }
            if (retryHint.containsKey("preferred_sources")) {
                if (!(retryHint.get("preferred_sources") instanceof List<?>)) {
                    throw new ContractViolationException(
                        "payload.retry_hint.preferred_sources must be an array");
                }
            }
            if (retryHint.containsKey("max_observation_age_ms")) {
                requireInteger(retryHint.get("max_observation_age_ms"),
                    "payload.retry_hint.max_observation_age_ms must be an integer");
            }
        }
    }

    private void validateExecutionPayload(Map<String, Object> payload) {
        requireUuid(requireString(payload.get("decision_event_id"), "payload.decision_event_id is required"),
            "payload.decision_event_id must be a valid UUID");
        requireString(payload.get("execution_id"), "payload.execution_id is required");
        String status = requireString(payload.get("status"), "payload.status is required");
        if (!List.of("success", "failed", "timeout", "partial").contains(status)) {
            throw new ContractViolationException("payload.status is invalid");
        }
        requireString(payload.get("executor"), "payload.executor is required");
    }

    private void validateObservationPayload(Map<String, Object> payload) {
        requireString(payload.get("source_tool"), "payload.source_tool is required");
        requireString(payload.get("evidence_ref"), "payload.evidence_ref is required");
        requireNumber(payload.get("confidence"), "payload.confidence is required");
    }

    private void validateToolCallPayload(Map<String, Object> payload) {
        requireString(payload.get("tool_name"), "payload.tool_name is required");
        requireString(payload.get("caller_role"), "payload.caller_role is required");
        requireString(payload.get("args_hash"), "payload.args_hash is required");
        requireString(payload.get("started_at"), "payload.started_at is required");
    }

    private void validateToolResultPayload(Map<String, Object> payload) {
        requireString(payload.get("tool_name"), "payload.tool_name is required");
        String status = requireString(payload.get("status"), "payload.status is required");
        if (!List.of("success", "failed").contains(status)) {
            throw new ContractViolationException("payload.status must be success or failed");
        }
        requireString(payload.get("result_hash"), "payload.result_hash is required");
    }

    private void validateDiagnosticPayload(Map<String, Object> payload) {
        requireString(payload.get("diagnostic_type"), "payload.diagnostic_type is required");
        requireString(payload.get("state_version"), "payload.state_version is required");
    }

    private String requireString(Object value, String message) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ContractViolationException(message);
        }
        return text;
    }

    private Object requireObject(Object value, String message) {
        if (!(value instanceof Map<?, ?>)) {
            throw new ContractViolationException(message);
        }
        return value;
    }

    private List<?> requireList(Object value, String message) {
        if (!(value instanceof List<?> list)) {
            throw new ContractViolationException(message);
        }
        return list;
    }

    private void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new ContractViolationException(message);
        }
    }

    private void requireUuid(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ContractViolationException(message);
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ContractViolationException(message);
        }
    }

    private void requireNumber(Object value, String message) {
        if (!(value instanceof Number)) {
            throw new ContractViolationException(message);
        }
    }

    private void requireInteger(Object value, String message) {
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new ContractViolationException(message);
        }
    }
}

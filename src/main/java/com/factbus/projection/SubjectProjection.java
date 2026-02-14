package com.factbus.projection;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * Read-only state projection for a single subject (DESIGN.md §10).
 *
 * Composition:
 * - Confirmed Facts: derived from FACT_EVENTs
 * - Pending Decisions: approved but not yet fully executed
 * - Pending Executions: execution in progress (no derived fact yet)
 *
 * The projection version is the highest sequence_number consumed.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SubjectProjection(
    @JsonProperty("subject_type") String subjectType,
    @JsonProperty("subject_id") String subjectId,
    @JsonProperty("projection_version") long projectionVersion,
    @JsonProperty("confirmed_facts") List<FactSnapshot> confirmedFacts,
    @JsonProperty("pending_decisions") List<DecisionSnapshot> pendingDecisions,
    @JsonProperty("pending_executions") List<ExecutionSnapshot> pendingExecutions
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FactSnapshot(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_name") String eventName,
        @JsonProperty("sequence_number") long sequenceNumber,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("observed_from") String observedFrom,
        @JsonProperty("facts") Map<String, Object> facts
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DecisionSnapshot(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("decision_id") String decisionId,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("sequence_number") long sequenceNumber,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("proposal_ids") List<String> proposalIds
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExecutionSnapshot(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("execution_id") String executionId,
        @JsonProperty("status") String status,
        @JsonProperty("sequence_number") long sequenceNumber,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("decision_event_id") String decisionEventId
    ) {}
}

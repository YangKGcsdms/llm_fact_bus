package com.factbus.projection;

import com.factbus.contract.EventCategory;
import com.factbus.contract.EventEnvelope;
import com.factbus.bus.EventStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/**
 * Projection Service — builds per-subject state projections from the Event Log.
 *
 * Per DESIGN.md §10:
 * - Projection is event-driven (rebuilt from event store)
 * - Composition: Confirmed Facts + Pending Decisions + Pending Executions
 * - Each projection tracks its version (highest consumed sequence_number)
 * - Derivation-version binding: FDR-derived facts carry derivation_rule_version
 *
 * Per §10.1 (Pending Decisions View):
 * - Any approved decision without a corresponding terminal execution is "pending"
 * - Any execution without a corresponding derived fact is "pending"
 */
@Service
public class ProjectionService {

    private final EventStore eventStore;

    public ProjectionService(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * Build projection for a subject by scanning all events.
     * For MVP this is a full scan; production would use incremental updates.
     */
    public Optional<SubjectProjection> getProjection(String subjectType, String subjectId) {
        List<EventEnvelope> allEvents = eventStore.query(
            Optional.empty(),
            Optional.empty(),
            Optional.of(subjectType),
            Optional.of(subjectId),
            10000
        );

        if (allEvents.isEmpty()) {
            return Optional.empty();
        }

        List<SubjectProjection.FactSnapshot> confirmedFacts = new ArrayList<>();
        Map<String, EventEnvelope> approvedDecisions = new LinkedHashMap<>(); // decision_id -> event
        Map<String, EventEnvelope> executions = new LinkedHashMap<>(); // execution_id -> event
        Set<String> executionDecisionEventIds = new HashSet<>(); // decision_event_ids with executions
        Set<String> derivedFactExecutionIds = new HashSet<>(); // execution_ids with derived facts

        long maxSequence = 0;

        for (EventEnvelope event : allEvents) {
            if (event.getSequenceNumber() != null && event.getSequenceNumber() > maxSequence) {
                maxSequence = event.getSequenceNumber();
            }

            switch (event.getEventCategory()) {
                case FACT_EVENT -> {
                    Map<String, Object> payload = event.getPayload();
                    String observedFrom = payload != null ? String.valueOf(payload.get("observed_from")) : "";

                    @SuppressWarnings("unchecked")
                    Map<String, Object> facts = payload != null && payload.get("facts") instanceof Map
                        ? (Map<String, Object>) payload.get("facts")
                        : Map.of();

                    confirmedFacts.add(new SubjectProjection.FactSnapshot(
                        event.getEventId(),
                        event.getEventName(),
                        event.getSequenceNumber() != null ? event.getSequenceNumber() : 0,
                        event.getOccurredAt() != null ? event.getOccurredAt().toString() : "",
                        observedFrom,
                        facts
                    ));

                    // Track FDR-derived facts to resolve pending executions
                    if ("executor_feedback".equals(observedFrom) && payload != null) {
                        String execId = String.valueOf(payload.get("execution_id"));
                        if (execId != null && !"null".equals(execId)) {
                            derivedFactExecutionIds.add(execId);
                        }
                    }
                }
                case DECISION_EVENT -> {
                    Map<String, Object> payload = event.getPayload();
                    String outcome = payload != null ? String.valueOf(payload.get("outcome")) : "";
                    if ("approved".equals(outcome)) {
                        String decisionId = payload != null ? String.valueOf(payload.get("decision_id")) : "";
                        approvedDecisions.put(event.getEventId(), event);
                    }
                }
                case EXECUTION_EVENT -> {
                    Map<String, Object> payload = event.getPayload();
                    String executionId = payload != null ? String.valueOf(payload.get("execution_id")) : "";
                    String decisionEventId = payload != null ? String.valueOf(payload.get("decision_event_id")) : "";
                    executions.put(executionId, event);
                    executionDecisionEventIds.add(decisionEventId);
                }
                default -> { /* governance/tool events don't affect projection state */ }
            }
        }

        // Pending decisions: approved but no execution event references them
        List<SubjectProjection.DecisionSnapshot> pendingDecisions = new ArrayList<>();
        for (Map.Entry<String, EventEnvelope> entry : approvedDecisions.entrySet()) {
            if (!executionDecisionEventIds.contains(entry.getKey())) {
                EventEnvelope dec = entry.getValue();
                Map<String, Object> payload = dec.getPayload();
                @SuppressWarnings("unchecked")
                List<String> proposalIds = payload != null && payload.get("decision_on_proposals") instanceof List
                    ? (List<String>) payload.get("decision_on_proposals")
                    : List.of();

                pendingDecisions.add(new SubjectProjection.DecisionSnapshot(
                    dec.getEventId(),
                    payload != null ? String.valueOf(payload.get("decision_id")) : "",
                    "approved",
                    dec.getSequenceNumber() != null ? dec.getSequenceNumber() : 0,
                    dec.getOccurredAt() != null ? dec.getOccurredAt().toString() : "",
                    proposalIds
                ));
            }
        }

        // Pending executions: execution event exists but no derived fact yet
        List<SubjectProjection.ExecutionSnapshot> pendingExecutions = new ArrayList<>();
        for (Map.Entry<String, EventEnvelope> entry : executions.entrySet()) {
            if (!derivedFactExecutionIds.contains(entry.getKey())) {
                EventEnvelope exec = entry.getValue();
                Map<String, Object> payload = exec.getPayload();
                pendingExecutions.add(new SubjectProjection.ExecutionSnapshot(
                    exec.getEventId(),
                    entry.getKey(),
                    payload != null ? String.valueOf(payload.get("status")) : "",
                    exec.getSequenceNumber() != null ? exec.getSequenceNumber() : 0,
                    exec.getOccurredAt() != null ? exec.getOccurredAt().toString() : "",
                    payload != null ? String.valueOf(payload.get("decision_event_id")) : ""
                ));
            }
        }

        return Optional.of(new SubjectProjection(
            subjectType, subjectId, maxSequence,
            confirmedFacts, pendingDecisions, pendingExecutions
        ));
    }
}

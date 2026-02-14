package com.factbus.integration;

import com.factbus.bus.EventBusService;
import com.factbus.contract.EventCategory;
import com.factbus.contract.EventEnvelope;
import com.factbus.contract.ProducerType;
import com.factbus.projection.ProjectionService;
import com.factbus.projection.SubjectProjection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E Rejection-path integration test (Step 8, Use Case 2):
 *
 * Gateway -> FACT_EVENT -> Agent PROPOSAL(critical risk, high cost)
 * -> Arbitration DECISION(rejected, retry_hint)
 * -> Agent OBSERVATION (supplementary) -> Agent PROPOSAL (re-propose, lower risk)
 * -> Arbitration DECISION(approved)
 * -> Execution(success) -> FDR -> FACT_EVENT
 */
@SpringBootTest
class RejectionPathIntegrationTest {

    @Autowired EventBusService bus;
    @Autowired ProjectionService projection;

    @Test
    @DisplayName("Rejection path: proposal rejected → observation → re-proposal → approved → execute → FDR")
    void rejectionPath_reproposalAfterRejection() {
        String traceId = "trace-reject-" + UUID.randomUUID().toString().substring(0, 8);
        String subjectType = "order";
        String subjectId = "ORD-RJ-001";

        // 1. Gateway injects initial FACT_EVENT
        EventEnvelope initialFact = buildFact(traceId, subjectType, subjectId,
            "HighValueRefundRequested",
            Map.of("order_id", subjectId, "amount", 50000));
        EventEnvelope publishedFact = bus.publish(initialFact);

        // 2. Agent publishes PROPOSAL with critical risk + high cost (should be rejected)
        EventEnvelope badProposal = buildProposal(traceId, subjectType, subjectId,
            "ProposedHighValueRefund",
            publishedFact.getEventId(),
            Map.of("type", "refund", "amount", 50000),
            "critical", 50000);
        bus.publish(badProposal);

        // 3. Verify rejection
        List<EventEnvelope> decisions = bus.query(
            Optional.of(traceId),
            Optional.of(EventCategory.DECISION_EVENT),
            Optional.empty(), Optional.empty(), 10);
        assertFalse(decisions.isEmpty());
        EventEnvelope rejectionDecision = decisions.stream()
            .filter(d -> "rejected".equals(d.getPayload().get("outcome")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected a rejected decision"));

        // Verify retry_hint structure
        Object retryHintRaw = rejectionDecision.getPayload().get("retry_hint");
        assertInstanceOf(Map.class, retryHintRaw, "retry_hint must be a structured object");
        assertEquals("CRITICAL_RISK_COST_EXCEEDED", rejectionDecision.getPayload().get("reason_code"));

        // 4. Agent publishes supplementary OBSERVATION_EVENT (simulating tool-based evidence)
        EventEnvelope observation = buildObservation(traceId, subjectType, subjectId,
            "CustomerHistoryObserved",
            rejectionDecision.getEventId());
        EventEnvelope publishedObs = bus.publish(observation);

        // 5. Agent re-proposes with lower risk level (should pass)
        EventEnvelope goodProposal = buildProposal(traceId, subjectType, subjectId,
            "ProposedRefundRevised",
            publishedFact.getEventId(),
            Map.of("type", "refund", "amount", 5000),
            "medium", 5000);
        // Add the observation to based_on_events
        @SuppressWarnings("unchecked")
        List<String> basedOn = (List<String>) goodProposal.getPayload().get("based_on_events");
        goodProposal.getPayload().put("based_on_events",
            List.of(publishedFact.getEventId(), publishedObs.getEventId()));
        goodProposal.setCausationId(rejectionDecision.getEventId());
        bus.publish(goodProposal);

        // 6. Verify approval
        List<EventEnvelope> allDecisions = bus.query(
            Optional.of(traceId),
            Optional.of(EventCategory.DECISION_EVENT),
            Optional.empty(), Optional.empty(), 100);
        EventEnvelope approvalDecision = allDecisions.stream()
            .filter(d -> "approved".equals(d.getPayload().get("outcome")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected an approved decision after re-proposal"));

        // 7. Executor publishes EXECUTION_EVENT(success)
        EventEnvelope execution = buildExecution(traceId, subjectType, subjectId,
            "RefundExecutionSucceeded",
            approvalDecision.getEventId(),
            "success", "exe-rj-001");
        bus.publish(execution);

        // 8. Verify FDR-derived fact exists
        List<EventEnvelope> facts = bus.query(
            Optional.of(traceId),
            Optional.of(EventCategory.FACT_EVENT),
            Optional.empty(), Optional.empty(), 100);
        boolean hasDerivedFact = facts.stream()
            .anyMatch(f -> "executor_feedback".equals(f.getPayload().get("observed_from")));
        assertTrue(hasDerivedFact, "FDR should have derived a fact from the successful execution");

        // 9. Verify full event trace
        List<EventEnvelope> allEvents = bus.query(
            Optional.of(traceId), Optional.empty(),
            Optional.empty(), Optional.empty(), 1000);
        // Expected: initial_fact, bad_proposal, rejection, observation,
        //           good_proposal, approval, execution, fdr_fact = at least 8 events
        assertTrue(allEvents.size() >= 8,
            "Full trace should have at least 8 events, got " + allEvents.size());

        // 10. Verify projection completeness
        Optional<SubjectProjection> proj = projection.getProjection(subjectType, subjectId);
        assertTrue(proj.isPresent());
        SubjectProjection p = proj.get();
        assertTrue(p.confirmedFacts().size() >= 2);
        assertTrue(p.pendingExecutions().isEmpty(),
            "No pending executions after FDR derivation");
    }

    // ---- helpers ----

    private EventEnvelope buildFact(String traceId, String subjectType, String subjectId,
                                     String eventName, Map<String, Object> facts) {
        EventEnvelope e = new EventEnvelope();
        e.setSchemaVersion("1.0.0");
        e.setEventId(UUID.randomUUID().toString());
        e.setEventCategory(EventCategory.FACT_EVENT);
        e.setEventName(eventName);
        e.setOccurredAt(Instant.now());
        e.setTraceId(traceId);
        EventEnvelope.Producer p = new EventEnvelope.Producer();
        p.setType(ProducerType.SYSTEM);
        p.setId("gateway");
        p.setVersion("test-v1");
        e.setProducer(p);
        EventEnvelope.Subject s = new EventEnvelope.Subject();
        s.setType(subjectType);
        s.setId(subjectId);
        e.setSubject(s);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("facts", facts);
        payload.put("observed_from", "api");
        e.setPayload(payload);
        return e;
    }

    private EventEnvelope buildProposal(String traceId, String subjectType, String subjectId,
                                         String eventName, String basedOnEventId,
                                         Map<String, Object> proposedAction,
                                         String riskLevel, double costEstimate) {
        EventEnvelope e = new EventEnvelope();
        e.setSchemaVersion("1.0.0");
        e.setEventId(UUID.randomUUID().toString());
        e.setEventCategory(EventCategory.PROPOSAL_EVENT);
        e.setEventName(eventName);
        e.setOccurredAt(Instant.now());
        e.setTraceId(traceId);
        e.setCausationId(basedOnEventId);
        EventEnvelope.Producer p = new EventEnvelope.Producer();
        p.setType(ProducerType.AGENT);
        p.setId("test-agent");
        p.setVersion("test-v1");
        e.setProducer(p);
        EventEnvelope.Subject s = new EventEnvelope.Subject();
        s.setType(subjectType);
        s.setId(subjectId);
        e.setSubject(s);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposal_id", "prp-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("proposed_action", proposedAction);
        payload.put("based_on_events", List.of(basedOnEventId));
        payload.put("risk_level", riskLevel);
        payload.put("cost_estimate", costEstimate);
        payload.put("priority", 80);
        payload.put("max_fact_age_ms", 600000);
        e.setPayload(payload);
        return e;
    }

    private EventEnvelope buildObservation(String traceId, String subjectType, String subjectId,
                                            String eventName, String causationId) {
        EventEnvelope e = new EventEnvelope();
        e.setSchemaVersion("1.0.0");
        e.setEventId(UUID.randomUUID().toString());
        e.setEventCategory(EventCategory.OBSERVATION_EVENT);
        e.setEventName(eventName);
        e.setOccurredAt(Instant.now());
        e.setTraceId(traceId);
        e.setCausationId(causationId);
        EventEnvelope.Producer p = new EventEnvelope.Producer();
        p.setType(ProducerType.AGENT);
        p.setId("test-agent");
        p.setVersion("test-v1");
        e.setProducer(p);
        EventEnvelope.Subject s = new EventEnvelope.Subject();
        s.setType(subjectType);
        s.setId(subjectId);
        e.setSubject(s);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_tool", "customer-history-tool");
        payload.put("evidence_ref", "tool-result-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("confidence", 0.92);
        payload.put("extracted_fields", Map.of("loyalty_tier", "gold", "order_count", 47));
        e.setPayload(payload);
        return e;
    }

    private EventEnvelope buildExecution(String traceId, String subjectType, String subjectId,
                                          String eventName, String decisionEventId,
                                          String status, String executionId) {
        EventEnvelope e = new EventEnvelope();
        e.setSchemaVersion("1.0.0");
        e.setEventId(UUID.randomUUID().toString());
        e.setEventCategory(EventCategory.EXECUTION_EVENT);
        e.setEventName(eventName);
        e.setOccurredAt(Instant.now());
        e.setTraceId(traceId);
        e.setCausationId(decisionEventId);
        EventEnvelope.Producer p = new EventEnvelope.Producer();
        p.setType(ProducerType.EXECUTOR);
        p.setId("test-executor");
        p.setVersion("test-v1");
        e.setProducer(p);
        EventEnvelope.Subject s = new EventEnvelope.Subject();
        s.setType(subjectType);
        s.setId(subjectId);
        e.setSubject(s);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision_event_id", decisionEventId);
        payload.put("execution_id", executionId);
        payload.put("status", status);
        payload.put("executor", "test-executor");
        e.setPayload(payload);
        return e;
    }
}

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
 * E2E Happy-path integration test (Step 8, Use Case 1):
 *
 * Gateway -> FACT_EVENT -> Agent PROPOSAL -> Arbitration DECISION(approved)
 * -> Executor EXECUTION(success) -> FDR -> FACT_EVENT(derived)
 * -> Projection reflects confirmed facts
 */
@SpringBootTest
class HappyPathIntegrationTest {

    @Autowired EventBusService bus;
    @Autowired ProjectionService projection;

    @Test
    @DisplayName("Happy path: gateway → proposal → approve → execute → FDR → projection")
    void happyPath_gatewayToFactDerivation() {
        String traceId = "trace-happy-" + UUID.randomUUID().toString().substring(0, 8);
        String subjectType = "order";
        String subjectId = "ORD-HP-001";

        // 1. Gateway injects initial FACT_EVENT (cold start)
        EventEnvelope initialFact = buildFact(traceId, subjectType, subjectId,
            "RefundRequested",
            Map.of("order_id", subjectId, "reason", "customer_request"),
            "api", ProducerType.SYSTEM, "gateway");
        EventEnvelope publishedFact = bus.publish(initialFact);
        assertNotNull(publishedFact.getSequenceNumber());
        assertTrue(publishedFact.getSequenceNumber() > 0);

        // 2. Agent publishes PROPOSAL_EVENT (references the initial fact)
        EventEnvelope proposal = buildProposal(traceId, subjectType, subjectId,
            "ProposedRefund",
            publishedFact.getEventId(),
            Map.of("type", "refund", "amount", 500));
        EventEnvelope publishedProposal = bus.publish(proposal);
        assertNotNull(publishedProposal.getSequenceNumber());

        // 3. Verify arbitration auto-produced a DECISION_EVENT (approved)
        List<EventEnvelope> decisions = bus.query(
            Optional.of(traceId),
            Optional.of(EventCategory.DECISION_EVENT),
            Optional.empty(), Optional.empty(), 10);
        assertFalse(decisions.isEmpty(), "Should have at least one decision");
        EventEnvelope decision = decisions.get(0);
        assertEquals("approved", decision.getPayload().get("outcome"));

        // 4. Executor publishes EXECUTION_EVENT (success)
        EventEnvelope execution = buildExecution(traceId, subjectType, subjectId,
            "RefundExecutionSucceeded",
            decision.getEventId(),
            "success", "exe-hp-001");
        EventEnvelope publishedExecution = bus.publish(execution);
        assertNotNull(publishedExecution.getSequenceNumber());

        // 5. Verify FDR auto-derived a FACT_EVENT
        List<EventEnvelope> derivedFacts = bus.query(
            Optional.of(traceId),
            Optional.of(EventCategory.FACT_EVENT),
            Optional.empty(), Optional.empty(), 100);
        // Should have at least 2 FACT_EVENTs: initial + FDR-derived
        assertTrue(derivedFacts.size() >= 2,
            "Should have initial fact + FDR-derived fact, got " + derivedFacts.size());

        EventEnvelope fdrFact = derivedFacts.stream()
            .filter(e -> "executor_feedback".equals(e.getPayload().get("observed_from")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No FDR-derived fact found"));
        assertEquals("system", fdrFact.getProducer().getType().getValue());
        assertEquals("fact-derivation-reactor", fdrFact.getProducer().getId());
        assertEquals(publishedExecution.getEventId(), fdrFact.getCausationId());
        assertNotNull(fdrFact.getPayload().get("derivation_rule_id"));
        assertNotNull(fdrFact.getPayload().get("derivation_rule_version"));
        assertNotNull(fdrFact.getPayload().get("decision_id"));
        assertNotNull(fdrFact.getPayload().get("execution_id"));

        // 6. Verify projection is up to date
        Optional<SubjectProjection> proj = projection.getProjection(subjectType, subjectId);
        assertTrue(proj.isPresent());
        SubjectProjection p = proj.get();
        assertEquals(subjectType, p.subjectType());
        assertEquals(subjectId, p.subjectId());
        assertTrue(p.projectionVersion() > 0);
        assertTrue(p.confirmedFacts().size() >= 2, "Projection should contain 2+ confirmed facts");
        // After FDR, execution should no longer be pending (derived fact exists)
        assertTrue(p.pendingExecutions().isEmpty(),
            "Pending executions should be empty after FDR derivation");
    }

    // ---- helpers ----

    private EventEnvelope buildFact(String traceId, String subjectType, String subjectId,
                                     String eventName, Map<String, Object> facts,
                                     String observedFrom, ProducerType producerType, String producerId) {
        EventEnvelope e = new EventEnvelope();
        e.setSchemaVersion("1.0.0");
        e.setEventId(UUID.randomUUID().toString());
        e.setEventCategory(EventCategory.FACT_EVENT);
        e.setEventName(eventName);
        e.setOccurredAt(Instant.now());
        e.setTraceId(traceId);
        EventEnvelope.Producer p = new EventEnvelope.Producer();
        p.setType(producerType);
        p.setId(producerId);
        p.setVersion("test-v1");
        e.setProducer(p);
        EventEnvelope.Subject s = new EventEnvelope.Subject();
        s.setType(subjectType);
        s.setId(subjectId);
        e.setSubject(s);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("facts", facts);
        payload.put("observed_from", observedFrom);
        e.setPayload(payload);
        return e;
    }

    private EventEnvelope buildProposal(String traceId, String subjectType, String subjectId,
                                         String eventName, String basedOnEventId,
                                         Map<String, Object> proposedAction) {
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
        payload.put("risk_level", "medium");
        payload.put("cost_estimate", 500);
        payload.put("priority", 80);
        payload.put("max_fact_age_ms", 600000);
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

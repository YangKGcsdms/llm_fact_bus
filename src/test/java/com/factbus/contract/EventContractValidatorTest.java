package com.factbus.contract;

import com.factbus.bus.InMemoryEventStore;
import com.factbus.bus.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventContractValidatorTest {

    private EventContractValidator validator;
    private EventStore eventStore;

    @BeforeEach
    void setUp() {
        validator = new EventContractValidator();
        eventStore = new InMemoryEventStore();
    }

    @Nested
    @DisplayName("Producer-Category Permission Matrix")
    class ProducerPermissions {

        @Test
        void sensor_canOnlyPublishFactEvent() {
            EventEnvelope event = envelope(EventCategory.PROPOSAL_EVENT, ProducerType.SENSOR);
            event.setPayload(Map.of("proposal_id", "x"));
            ContractViolationException ex = assertThrows(ContractViolationException.class,
                () -> validator.validate(event, eventStore));
            assertTrue(ex.getMessage().contains("sensor"));
        }

        @Test
        void api_canOnlyPublishFactEvent() {
            EventEnvelope event = envelope(EventCategory.DECISION_EVENT, ProducerType.API);
            event.setPayload(Map.of("decision_id", "x"));
            ContractViolationException ex = assertThrows(ContractViolationException.class,
                () -> validator.validate(event, eventStore));
            assertTrue(ex.getMessage().contains("api"));
        }

        @Test
        void databaseSnapshot_canOnlyPublishFactEvent() {
            EventEnvelope event = envelope(EventCategory.OBSERVATION_EVENT, ProducerType.DATABASE_SNAPSHOT);
            event.setPayload(Map.of("source_tool", "x"));
            ContractViolationException ex = assertThrows(ContractViolationException.class,
                () -> validator.validate(event, eventStore));
            assertTrue(ex.getMessage().contains("database_snapshot"));
        }

        @Test
        void system_canPublishFactEvent() {
            EventEnvelope event = envelope(EventCategory.FACT_EVENT, ProducerType.SYSTEM);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("facts", Map.of("key", "value"));
            payload.put("observed_from", "api");
            event.setPayload(payload);
            assertDoesNotThrow(() -> validator.validate(event, eventStore));
        }

        @Test
        void system_cannotPublishProposalEvent() {
            EventEnvelope event = envelope(EventCategory.PROPOSAL_EVENT, ProducerType.SYSTEM);
            event.setPayload(Map.of("proposal_id", "x"));
            assertThrows(ContractViolationException.class,
                () -> validator.validate(event, eventStore));
        }

        @Test
        void agent_canPublishProposal() {
            // Setup: need an existing event for based_on_events
            EventEnvelope fact = envelope(EventCategory.FACT_EVENT, ProducerType.SYSTEM);
            fact.setPayload(Map.of("facts", Map.of("k", "v"), "observed_from", "api"));
            eventStore.append(fact);

            EventEnvelope proposal = envelope(EventCategory.PROPOSAL_EVENT, ProducerType.AGENT);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("proposal_id", "prp-1");
            payload.put("proposed_action", Map.of("type", "refund"));
            payload.put("based_on_events", List.of(fact.getEventId()));
            payload.put("risk_level", "low");
            payload.put("cost_estimate", 100);
            payload.put("priority", 50);
            payload.put("max_fact_age_ms", 60000);
            proposal.setPayload(payload);
            assertDoesNotThrow(() -> validator.validate(proposal, eventStore));
        }
    }

    @Nested
    @DisplayName("FACT_EVENT derivation fields")
    class FactDerivation {

        @Test
        void executorFeedback_requiresDerivationFields() {
            EventEnvelope event = envelope(EventCategory.FACT_EVENT, ProducerType.SYSTEM);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("facts", Map.of("k", "v"));
            payload.put("observed_from", "executor_feedback");
            // Missing derivation fields
            event.setPayload(payload);
            assertThrows(ContractViolationException.class,
                () -> validator.validate(event, eventStore));
        }

        @Test
        void executorFeedback_passesWithAllDerivationFields() {
            EventEnvelope event = envelope(EventCategory.FACT_EVENT, ProducerType.SYSTEM);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("facts", Map.of("k", "v"));
            payload.put("observed_from", "executor_feedback");
            payload.put("derivation_rule_id", "rule-1");
            payload.put("derivation_rule_version", "v1");
            payload.put("decision_id", "dec-1");
            payload.put("execution_id", "exe-1");
            event.setPayload(payload);
            assertDoesNotThrow(() -> validator.validate(event, eventStore));
        }

        @Test
        void humanInput_isValidObservedFrom() {
            EventEnvelope event = envelope(EventCategory.FACT_EVENT, ProducerType.SYSTEM);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("facts", Map.of("k", "v"));
            payload.put("observed_from", "human_input");
            event.setPayload(payload);
            assertDoesNotThrow(() -> validator.validate(event, eventStore));
        }
    }

    @Nested
    @DisplayName("DECISION_EVENT rejection fields")
    class DecisionRejection {

        @Test
        void rejected_requiresStructuredRetryHint() {
            EventEnvelope event = envelope(EventCategory.DECISION_EVENT, ProducerType.ARBITRATOR);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decision_id", "dec-1");
            payload.put("decision_on_proposals", List.of("prp-1"));
            payload.put("outcome", "rejected");
            payload.put("policy_id", "policy-1");
            payload.put("policy_version", "v1");
            payload.put("reason_code", "RISK_TOO_HIGH");
            // Missing retry_hint
            event.setPayload(payload);
            assertThrows(ContractViolationException.class,
                () -> validator.validate(event, eventStore));
        }

        @Test
        void rejected_passesWithRetryHintObject() {
            EventEnvelope event = envelope(EventCategory.DECISION_EVENT, ProducerType.ARBITRATOR);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decision_id", "dec-1");
            payload.put("decision_on_proposals", List.of("prp-1"));
            payload.put("outcome", "rejected");
            payload.put("policy_id", "policy-1");
            payload.put("policy_version", "v1");
            payload.put("reason_code", "RISK_TOO_HIGH");
            payload.put("retry_hint", Map.of(
                "missing_fact_keys", List.of("customer_tier"),
                "required_trust_tier", "tier_1"
            ));
            event.setPayload(payload);
            assertDoesNotThrow(() -> validator.validate(event, eventStore));
        }

        @Test
        void approved_doesNotRequireRetryHint() {
            EventEnvelope event = envelope(EventCategory.DECISION_EVENT, ProducerType.ARBITRATOR);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decision_id", "dec-1");
            payload.put("decision_on_proposals", List.of("prp-1"));
            payload.put("outcome", "approved");
            payload.put("policy_id", "policy-1");
            payload.put("policy_version", "v1");
            payload.put("reason_code", "ALL_PASSED");
            event.setPayload(payload);
            assertDoesNotThrow(() -> validator.validate(event, eventStore));
        }
    }

    @Nested
    @DisplayName("PROPOSAL_EVENT max_fact_age_ms")
    class ProposalMaxFactAge {

        @Test
        void proposal_requiresMaxFactAgeMs() {
            EventEnvelope fact = envelope(EventCategory.FACT_EVENT, ProducerType.SYSTEM);
            fact.setPayload(Map.of("facts", Map.of("k", "v"), "observed_from", "api"));
            eventStore.append(fact);

            EventEnvelope proposal = envelope(EventCategory.PROPOSAL_EVENT, ProducerType.AGENT);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("proposal_id", "prp-1");
            payload.put("proposed_action", Map.of("type", "refund"));
            payload.put("based_on_events", List.of(fact.getEventId()));
            payload.put("risk_level", "low");
            payload.put("cost_estimate", 100);
            payload.put("priority", 50);
            // Missing max_fact_age_ms
            proposal.setPayload(payload);
            assertThrows(ContractViolationException.class,
                () -> validator.validate(proposal, eventStore));
        }
    }

    // ---- helper ----

    private EventEnvelope envelope(EventCategory category, ProducerType producerType) {
        EventEnvelope e = new EventEnvelope();
        e.setSchemaVersion("1.0.0");
        e.setEventId(UUID.randomUUID().toString());
        e.setEventCategory(category);
        e.setEventName("TestEvent");
        e.setOccurredAt(Instant.now());
        e.setTraceId("trace-test");
        EventEnvelope.Producer p = new EventEnvelope.Producer();
        p.setType(producerType);
        p.setId("test-producer");
        p.setVersion("test-v1");
        e.setProducer(p);
        EventEnvelope.Subject s = new EventEnvelope.Subject();
        s.setType("test");
        s.setId("test-001");
        e.setSubject(s);
        return e;
    }
}

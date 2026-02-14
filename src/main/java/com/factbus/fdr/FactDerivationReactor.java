package com.factbus.fdr;

import com.factbus.contract.EventCategory;
import com.factbus.contract.EventEnvelope;
import com.factbus.contract.ProducerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fact Derivation Reactor (FDR) — Bus-internal deterministic reactor.
 *
 * Subscribes to EXECUTION_EVENTs and produces derived FACT_EVENTs using
 * versioned derivation rules. This is infrastructure logic, not an Agent.
 *
 * Per DESIGN.md §4.7.3:
 * - producer.type = "system"
 * - producer.id = "fact-derivation-reactor"
 * - producer.version = derivation_rule_version
 * - causation_id = source execution event id
 * - trace_id inherited from source execution event
 * - payload must include derivation_rule_id, derivation_rule_version,
 *   decision_id, execution_id
 */
public class FactDerivationReactor {

    private static final Logger log = LoggerFactory.getLogger(FactDerivationReactor.class);

    private static final String PRODUCER_ID = "fact-derivation-reactor";

    private final List<DerivationRule> rules;

    public FactDerivationReactor(List<DerivationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Attempts to derive a FACT_EVENT from the given EXECUTION_EVENT.
     *
     * @param executionEvent must be an EXECUTION_EVENT with sequence_number assigned
     * @return the derived FACT_EVENT envelope, or null if no rule matched
     */
    public EventEnvelope tryDerive(EventEnvelope executionEvent) {
        if (executionEvent.getEventCategory() != EventCategory.EXECUTION_EVENT) {
            return null;
        }

        for (DerivationRule rule : rules) {
            if (rule.matches(executionEvent)) {
                return buildDerivedFact(executionEvent, rule);
            }
        }

        log.warn("No derivation rule matched EXECUTION_EVENT event_id={}, status={}",
            executionEvent.getEventId(),
            executionEvent.getPayload() != null ? executionEvent.getPayload().get("status") : "null");
        return null;
    }

    private EventEnvelope buildDerivedFact(EventEnvelope source, DerivationRule rule) {
        DerivationRule.DerivedFact derived = rule.derive(source);
        Map<String, Object> sourcePayload = source.getPayload();

        // Build FACT_EVENT payload per DESIGN.md §4.7.3
        Map<String, Object> factPayload = new LinkedHashMap<>();
        factPayload.put("facts", derived.facts());
        factPayload.put("observed_from", "executor_feedback");
        factPayload.put("derivation_rule_id", rule.ruleId());
        factPayload.put("derivation_rule_version", rule.ruleVersion());
        factPayload.put("decision_id", String.valueOf(sourcePayload.get("decision_event_id")));
        factPayload.put("execution_id", String.valueOf(sourcePayload.get("execution_id")));

        // Build envelope
        EventEnvelope fact = new EventEnvelope();
        fact.setSchemaVersion(source.getSchemaVersion());
        fact.setEventId(UUID.randomUUID().toString());
        fact.setEventCategory(EventCategory.FACT_EVENT);
        fact.setEventName(derived.eventName());
        fact.setOccurredAt(Instant.now());
        fact.setTraceId(source.getTraceId());
        fact.setCausationId(source.getEventId());

        EventEnvelope.Producer producer = new EventEnvelope.Producer();
        producer.setType(ProducerType.SYSTEM);
        producer.setId(PRODUCER_ID);
        producer.setVersion(rule.ruleId() + "-" + rule.ruleVersion());
        fact.setProducer(producer);

        // Inherit subject from source execution event
        fact.setSubject(source.getSubject());
        fact.setPayload(factPayload);

        log.info("FDR derived FACT_EVENT: rule={}/{}, source_execution={}, derived_event={}",
            rule.ruleId(), rule.ruleVersion(), source.getEventId(), fact.getEventId());

        return fact;
    }
}

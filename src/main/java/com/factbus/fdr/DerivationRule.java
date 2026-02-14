package com.factbus.fdr;

import com.factbus.contract.EventEnvelope;

import java.util.Map;

/**
 * A single deterministic derivation rule that transforms an EXECUTION_EVENT
 * into a FACT_EVENT payload. Rules are versioned and must be pure functions
 * (no external calls, no side effects).
 */
public interface DerivationRule {

    /** Unique identifier for this rule, e.g. "refund-success-to-fact". */
    String ruleId();

    /** Semantic version, e.g. "v1". */
    String ruleVersion();

    /**
     * Whether this rule applies to the given execution event.
     * Typically matches on event_name and/or payload.status.
     */
    boolean matches(EventEnvelope executionEvent);

    /**
     * Derive the FACT_EVENT payload from the execution event.
     * Must be deterministic and must not call external systems.
     *
     * @param executionEvent the source EXECUTION_EVENT
     * @return the payload for the derived FACT_EVENT (must include "facts" and "observed_from")
     */
    DerivedFact derive(EventEnvelope executionEvent);

    /**
     * Result of a derivation: fact event name + payload fields.
     */
    record DerivedFact(
        String eventName,
        Map<String, Object> facts
    ) {}
}

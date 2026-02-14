package com.factbus.fdr;

import com.factbus.contract.EventEnvelope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default derivation rule that covers the 4 EXECUTION_EVENT status values
 * defined in the MVP: success, failed, partial, timeout.
 *
 * Each status maps to a corresponding derived FACT_EVENT with a deterministic
 * event name suffix and the original execution payload forwarded into facts.
 */
public class DefaultDerivationRule implements DerivationRule {

    private final String ruleId;
    private final String ruleVersion;
    private final String targetStatus;
    private final String factEventNameSuffix;

    public DefaultDerivationRule(String ruleId, String ruleVersion,
                                  String targetStatus, String factEventNameSuffix) {
        this.ruleId = ruleId;
        this.ruleVersion = ruleVersion;
        this.targetStatus = targetStatus;
        this.factEventNameSuffix = factEventNameSuffix;
    }

    @Override
    public String ruleId() {
        return ruleId;
    }

    @Override
    public String ruleVersion() {
        return ruleVersion;
    }

    @Override
    public boolean matches(EventEnvelope executionEvent) {
        if (executionEvent.getPayload() == null) {
            return false;
        }
        return targetStatus.equals(executionEvent.getPayload().get("status"));
    }

    @Override
    public DerivedFact derive(EventEnvelope executionEvent) {
        Map<String, Object> payload = executionEvent.getPayload();

        // Build the facts object from execution payload
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("execution_status", payload.get("status"));
        facts.put("executor", payload.get("executor"));
        if (payload.containsKey("external_reference")) {
            facts.put("external_reference", payload.get("external_reference"));
        }
        if (payload.containsKey("error_code")) {
            facts.put("error_code", payload.get("error_code"));
        }
        if (payload.containsKey("error_message")) {
            facts.put("error_message", payload.get("error_message"));
        }

        // Derive the event name: strip "Event" suffix if present, append fact suffix
        String baseName = executionEvent.getEventName();
        if (baseName != null && baseName.endsWith("Event")) {
            baseName = baseName.substring(0, baseName.length() - 5);
        }
        String eventName = baseName + factEventNameSuffix;

        return new DerivedFact(eventName, facts);
    }

    /**
     * Creates the standard set of 4 derivation rules for the MVP.
     */
    public static java.util.List<DerivationRule> mvpRules() {
        return java.util.List.of(
            new DefaultDerivationRule("execution-success-to-fact", "v1",
                "success", "Confirmed"),
            new DefaultDerivationRule("execution-failed-to-fact", "v1",
                "failed", "Failed"),
            new DefaultDerivationRule("execution-partial-to-fact", "v1",
                "partial", "PartiallyCompleted"),
            new DefaultDerivationRule("execution-timeout-to-fact", "v1",
                "timeout", "TimedOut")
        );
    }
}

package com.factbus.arbitration;

import com.factbus.contract.EventEnvelope;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Policy: verifies that all facts referenced in based_on_events
 * are within the proposal's declared max_fact_age_ms.
 */
public class FactFreshnessPolicy implements ArbitrationPolicy {

    @Override
    public String policyId() {
        return "fact-freshness-check";
    }

    @Override
    public String policyVersion() {
        return "v1";
    }

    @Override
    public PolicyResult evaluate(EventEnvelope proposal, List<EventEnvelope> currentFacts) {
        if (proposal.getPayload() == null) {
            return new PolicyResult.Reject("INVALID_PROPOSAL",
                RetryHint.empty());
        }

        Object maxAgeObj = proposal.getPayload().get("max_fact_age_ms");
        if (!(maxAgeObj instanceof Number maxAgeNum)) {
            return new PolicyResult.Pass(); // no age constraint declared, pass
        }

        long maxAgeMs = maxAgeNum.longValue();
        Instant proposalTime = proposal.getOccurredAt() != null ? proposal.getOccurredAt() : Instant.now();

        List<?> basedOnEvents = (List<?>) proposal.getPayload().get("based_on_events");
        if (basedOnEvents == null || basedOnEvents.isEmpty()) {
            return new PolicyResult.Pass();
        }

        for (EventEnvelope fact : currentFacts) {
            if (fact.getOccurredAt() == null) {
                continue;
            }
            long ageMs = Duration.between(fact.getOccurredAt(), proposalTime).toMillis();
            if (ageMs > maxAgeMs) {
                return new PolicyResult.Reject("STALE_FACT",
                    new RetryHint(
                        List.of(fact.getEventId()),
                        "tier_1",
                        List.of("db", "api"),
                        maxAgeMs
                    ));
            }
        }

        return new PolicyResult.Pass();
    }
}

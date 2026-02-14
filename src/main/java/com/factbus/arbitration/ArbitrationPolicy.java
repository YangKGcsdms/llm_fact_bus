package com.factbus.arbitration;

import com.factbus.contract.EventEnvelope;

import java.util.List;

/**
 * A single arbitration policy that evaluates a PROPOSAL_EVENT.
 * Policies are deterministic rules — no LLM, no randomness.
 */
public interface ArbitrationPolicy {

    /** Unique policy identifier, e.g. "max-risk-limit". */
    String policyId();

    /** Policy version, e.g. "v1". */
    String policyVersion();

    /**
     * Evaluate a proposal against this policy.
     *
     * @param proposal the PROPOSAL_EVENT to evaluate
     * @param currentFacts the current confirmed facts for the subject (from projection)
     * @return PASS if the proposal satisfies this policy, or a rejection result with details
     */
    PolicyResult evaluate(EventEnvelope proposal, List<EventEnvelope> currentFacts);

    /**
     * Result of policy evaluation.
     */
    sealed interface PolicyResult {
        record Pass() implements PolicyResult {}
        record Reject(String reasonCode, RetryHint retryHint) implements PolicyResult {}
    }

    /**
     * Structured retry hint for rejected proposals (DESIGN.md §6.3).
     */
    record RetryHint(
        List<String> missingFactKeys,
        String requiredTrustTier,
        List<String> preferredSources,
        Long maxObservationAgeMs
    ) {
        public static RetryHint empty() {
            return new RetryHint(List.of(), null, List.of(), null);
        }
    }
}

package com.factbus.arbitration;

import com.factbus.contract.EventCategory;
import com.factbus.contract.EventEnvelope;
import com.factbus.contract.ProducerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MVP Arbitration Service — deterministic, non-LLM arbitrator.
 *
 * Evaluates a PROPOSAL_EVENT against all registered policies.
 * Produces a DECISION_EVENT (approved or rejected) with structured feedback.
 *
 * Per DESIGN.md §6:
 * - Non-LLM: deterministic code/rules only
 * - Input: proposal + current projection facts
 * - Output: DECISION_EVENT(approved) or DECISION_EVENT(rejected) with retry_hint
 */
public class ArbitrationService {

    private static final Logger log = LoggerFactory.getLogger(ArbitrationService.class);

    private static final String ARBITRATOR_ID = "mvp-arbitrator";
    private static final String ARBITRATOR_VERSION = "v1";

    private final List<ArbitrationPolicy> policies;

    public ArbitrationService(List<ArbitrationPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    /**
     * Arbitrate a PROPOSAL_EVENT.
     *
     * @param proposal the PROPOSAL_EVENT to arbitrate
     * @param currentFacts confirmed facts for the proposal's subject
     * @return a DECISION_EVENT envelope (not yet appended to store)
     */
    public EventEnvelope arbitrate(EventEnvelope proposal, List<EventEnvelope> currentFacts) {
        if (proposal.getEventCategory() != EventCategory.PROPOSAL_EVENT) {
            throw new IllegalArgumentException("Can only arbitrate PROPOSAL_EVENTs");
        }

        String proposalId = proposal.getPayload() != null
            ? String.valueOf(proposal.getPayload().get("proposal_id"))
            : "unknown";

        // Evaluate all policies; first rejection wins
        for (ArbitrationPolicy policy : policies) {
            ArbitrationPolicy.PolicyResult result = policy.evaluate(proposal, currentFacts);
            if (result instanceof ArbitrationPolicy.PolicyResult.Reject reject) {
                log.info("Proposal {} rejected by policy {}/{}: {}",
                    proposalId, policy.policyId(), policy.policyVersion(), reject.reasonCode());
                return buildDecision(proposal, proposalId, "rejected",
                    policy.policyId(), policy.policyVersion(),
                    reject.reasonCode(), reject.retryHint());
            }
        }

        // All policies passed — approve
        log.info("Proposal {} approved by all {} policies", proposalId, policies.size());
        String firstPolicyId = policies.isEmpty() ? "no-policy" : policies.get(0).policyId();
        String firstPolicyVersion = policies.isEmpty() ? "v0" : policies.get(0).policyVersion();
        return buildDecision(proposal, proposalId, "approved",
            firstPolicyId, firstPolicyVersion, "ALL_POLICIES_PASSED", null);
    }

    private EventEnvelope buildDecision(EventEnvelope proposal, String proposalId,
                                         String outcome, String policyId, String policyVersion,
                                         String reasonCode, ArbitrationPolicy.RetryHint retryHint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision_id", "dec-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("decision_on_proposals", List.of(proposalId));
        payload.put("outcome", outcome);
        payload.put("policy_id", policyId);
        payload.put("policy_version", policyVersion);
        payload.put("reason_code", reasonCode);

        if ("rejected".equals(outcome) && retryHint != null) {
            Map<String, Object> hint = new LinkedHashMap<>();
            if (retryHint.missingFactKeys() != null && !retryHint.missingFactKeys().isEmpty()) {
                hint.put("missing_fact_keys", retryHint.missingFactKeys());
            }
            if (retryHint.requiredTrustTier() != null) {
                hint.put("required_trust_tier", retryHint.requiredTrustTier());
            }
            if (retryHint.preferredSources() != null && !retryHint.preferredSources().isEmpty()) {
                hint.put("preferred_sources", retryHint.preferredSources());
            }
            if (retryHint.maxObservationAgeMs() != null) {
                hint.put("max_observation_age_ms", retryHint.maxObservationAgeMs());
            }
            // Ensure retry_hint is never empty when rejected
            if (hint.isEmpty()) {
                hint.put("missing_fact_keys", List.of());
            }
            payload.put("retry_hint", hint);
        }

        // Collect active policy IDs
        List<String> activePolicyIds = new ArrayList<>();
        for (ArbitrationPolicy p : policies) {
            activePolicyIds.add(p.policyId());
        }
        payload.put("active_policy_ids", activePolicyIds);

        EventEnvelope decision = new EventEnvelope();
        decision.setSchemaVersion(proposal.getSchemaVersion());
        decision.setEventId(UUID.randomUUID().toString());
        decision.setEventCategory(EventCategory.DECISION_EVENT);
        decision.setEventName("rejected".equals(outcome) ? "ProposalRejected" : "ProposalApproved");
        decision.setOccurredAt(Instant.now());
        decision.setTraceId(proposal.getTraceId());
        decision.setCausationId(proposal.getEventId());

        EventEnvelope.Producer producer = new EventEnvelope.Producer();
        producer.setType(ProducerType.ARBITRATOR);
        producer.setId(ARBITRATOR_ID);
        producer.setVersion(ARBITRATOR_VERSION);
        decision.setProducer(producer);

        decision.setSubject(proposal.getSubject());
        decision.setPayload(payload);

        return decision;
    }
}

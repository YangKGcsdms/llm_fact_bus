package com.factbus.arbitration;

import com.factbus.contract.EventEnvelope;

import java.util.List;

/**
 * Policy: rejects critical-risk proposals that exceed a cost threshold
 * without sufficient evidence tier.
 */
public class RiskLimitPolicy implements ArbitrationPolicy {

    private final double criticalCostThreshold;

    public RiskLimitPolicy(double criticalCostThreshold) {
        this.criticalCostThreshold = criticalCostThreshold;
    }

    @Override
    public String policyId() {
        return "risk-cost-limit";
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

        String riskLevel = (String) proposal.getPayload().get("risk_level");
        Object costObj = proposal.getPayload().get("cost_estimate");

        if ("critical".equals(riskLevel) && costObj instanceof Number cost) {
            if (cost.doubleValue() > criticalCostThreshold) {
                return new PolicyResult.Reject("CRITICAL_RISK_COST_EXCEEDED",
                    new RetryHint(
                        List.of(),
                        "tier_1",
                        List.of(),
                        null
                    ));
            }
        }

        return new PolicyResult.Pass();
    }
}

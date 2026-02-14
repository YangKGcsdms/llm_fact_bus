package com.factbus.arbitration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ArbitrationConfiguration {

    /**
     * MVP arbitration service with two default policies:
     * 1. Fact freshness check (validates max_fact_age_ms)
     * 2. Risk-cost limit (rejects critical-risk proposals over threshold)
     */
    @Bean
    public ArbitrationService arbitrationService() {
        return new ArbitrationService(List.of(
            new FactFreshnessPolicy(),
            new RiskLimitPolicy(10000.0) // configurable threshold for MVP
        ));
    }
}

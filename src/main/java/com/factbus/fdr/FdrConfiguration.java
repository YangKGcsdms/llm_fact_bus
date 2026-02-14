package com.factbus.fdr;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FdrConfiguration {

    @Bean
    public FactDerivationReactor factDerivationReactor() {
        return new FactDerivationReactor(DefaultDerivationRule.mvpRules());
    }
}

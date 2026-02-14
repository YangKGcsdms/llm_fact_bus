package com.factbus.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ProducerType {
    SENSOR("sensor"),
    API("api"),
    DATABASE_SNAPSHOT("database_snapshot"),
    AGENT("agent"),
    ARBITRATOR("arbitrator"),
    EXECUTOR("executor"),
    SYSTEM("system");

    private final String value;

    ProducerType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ProducerType fromValue(String raw) {
        if (raw == null) {
            return null;
        }
        return Arrays.stream(values())
            .filter(v -> v.value.equalsIgnoreCase(raw))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown producer type: " + raw));
    }
}

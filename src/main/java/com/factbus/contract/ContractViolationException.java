package com.factbus.contract;

public class ContractViolationException extends RuntimeException {
    public ContractViolationException(String message) {
        super(message);
    }
}

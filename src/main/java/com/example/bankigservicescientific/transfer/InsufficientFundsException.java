package com.example.bankigservicescientific.transfer;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(long accountId) {
        super("Insufficient funds on account " + accountId);
    }
}

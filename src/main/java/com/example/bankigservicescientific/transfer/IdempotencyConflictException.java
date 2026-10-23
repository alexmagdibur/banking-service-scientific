package com.example.bankigservicescientific.transfer;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency-Key was already used with a different request");
    }
}

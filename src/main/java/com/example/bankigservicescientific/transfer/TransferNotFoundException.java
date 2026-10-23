package com.example.bankigservicescientific.transfer;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID id) {
        super("Transfer " + id + " not found");
    }
}

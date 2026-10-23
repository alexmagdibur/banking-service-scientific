package com.example.bankigservicescientific.transfer;

import java.time.Instant;
import java.util.UUID;

public record Transfer(UUID id, long fromAccountId, long toAccountId, long amountMinor, String status,
                       Instant createdAt) {
}

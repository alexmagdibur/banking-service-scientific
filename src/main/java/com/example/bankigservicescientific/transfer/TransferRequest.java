package com.example.bankigservicescientific.transfer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TransferRequest(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @NotNull @Min(TransferService.MIN_AMOUNT_MINOR) @Max(TransferService.MAX_AMOUNT_MINOR) Long amountMinor) {
}

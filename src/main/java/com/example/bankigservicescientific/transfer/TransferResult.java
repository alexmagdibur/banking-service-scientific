package com.example.bankigservicescientific.transfer;

/** {@code created} is true for a newly executed transfer and false for a replay of an earlier one. */
public record TransferResult(Transfer transfer, boolean created) {
}

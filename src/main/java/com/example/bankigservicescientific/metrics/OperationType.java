package com.example.bankigservicescientific.metrics;

/** The two kinds of business operation that are measured; the label value is the metric tag. */
public enum OperationType {

    BALANCE_VIEW("balance_view"),
    TRANSFER("transfer");

    private final String label;

    OperationType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

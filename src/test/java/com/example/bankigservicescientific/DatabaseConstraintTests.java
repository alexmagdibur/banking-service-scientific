package com.example.bankigservicescientific;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The invariants must hold in the database itself, even when the application code is bypassed. */
class DatabaseConstraintTests extends AbstractIntegrationTest {

    @Test
    void negativeBalanceIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE accounts SET balance_minor = -1 WHERE id = 1001").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(balance(1001)).isEqualTo(INITIAL);
    }

    @Test
    void invalidTransferRowsAreRejectedByTheDatabase() {
        assertThatThrownBy(() -> insert("k-self", 1001, 1001, 5)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("k-zero", 1001, 1002, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("k-neg", 1001, 1002, -5)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("k-unknown", 1001, 9999, 5)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(transferCount()).isZero();
    }

    @Test
    void duplicateIdempotencyKeyIsRejectedByTheDatabase() {
        insert("k-dup", 1001, 1002, 5);
        assertThatThrownBy(() -> insert("k-dup", 1002, 1003, 7)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(transferCount()).isEqualTo(1);
    }

    private void insert(String key, long from, long to, long amount) {
        jdbc.sql("""
                        INSERT INTO transfers (id, idempotency_key, from_account_id, to_account_id, amount_minor,
                                               request_fingerprint, status, created_at)
                        VALUES (:id, :key, :from, :to, :amount, 'f', 'COMPLETED', :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("key", key)
                .param("from", from)
                .param("to", to)
                .param("amount", amount)
                .param("now", OffsetDateTime.now())
                .update();
    }
}

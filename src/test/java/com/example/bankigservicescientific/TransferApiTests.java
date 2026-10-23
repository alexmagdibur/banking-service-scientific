package com.example.bankigservicescientific;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests of POST /api/transfers against the real PostgreSQL configured via BANK_DB_*.
 * WARNING: before every test the transfers table is emptied and the three test accounts are reset
 * to 10 000 000 kopecks, so run these only against a dedicated test database.
 * Every test checks the actual database state, not only the HTTP response.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferApiTests {

    private static final long INITIAL = 10_000_000;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void resetTestData() {
        jdbc.sql("DELETE FROM transfers").update();
        jdbc.sql("UPDATE accounts SET balance_minor = :b WHERE id IN (1001, 1002, 1003)").param("b", INITIAL).update();
    }

    @Test
    void successfulTransferMovesMoneyExactlyOnceAndStoresRecord() throws Exception {
        String key = UUID.randomUUID().toString();

        send(key, 1001, 1002, 250_000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.fromAccountId").value(1001))
                .andExpect(jsonPath("$.toAccountId").value(1002))
                .andExpect(jsonPath("$.amountMinor").value(250_000))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.createdAt").isString());

        assertThat(balance(1001)).isEqualTo(INITIAL - 250_000);
        assertThat(balance(1002)).isEqualTo(INITIAL + 250_000);
        assertThat(balance(1003)).isEqualTo(INITIAL);
        assertThat(totalBalance()).isEqualTo(3 * INITIAL);
        assertThat(transferCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT idempotency_key FROM transfers").query(String.class).single()).isEqualTo(key);
        assertThat(jdbc.sql("SELECT length(request_fingerprint) FROM transfers").query(Integer.class).single())
                .isEqualTo(64);
    }

    @Test
    void insufficientFundsGives409AndChangesNothing() throws Exception {
        jdbc.sql("UPDATE accounts SET balance_minor = 500 WHERE id = 1003").update();
        long total = totalBalance();

        send(UUID.randomUUID().toString(), 1003, 1001, 501)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertUnchanged(total, 500, 1003);
    }

    @Test
    void exactBalanceCanBeTransferredAndBalanceNeverGoesNegative() throws Exception {
        jdbc.sql("UPDATE accounts SET balance_minor = 500 WHERE id = 1003").update();

        send(UUID.randomUUID().toString(), 1003, 1001, 500).andExpect(status().isCreated());

        assertThat(balance(1003)).isZero();
        assertThat(balance(1001)).isEqualTo(INITIAL + 500);
    }

    @Test
    void transferToSelfGives400AndChangesNothing() throws Exception {
        send(UUID.randomUUID().toString(), 1001, 1001, 100)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertUnchanged(3 * INITIAL, INITIAL, 1001);
    }

    @Test
    void zeroNegativeAndTooLargeAmountsGive400AndChangeNothing() throws Exception {
        for (long amount : new long[]{0, -5, 1_000_001}) {
            send(UUID.randomUUID().toString(), 1001, 1002, amount)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertUnchanged(3 * INITIAL, INITIAL, 1001);
    }

    @Test
    void maximumAmountIsAccepted() throws Exception {
        send(UUID.randomUUID().toString(), 1001, 1002, 1_000_000).andExpect(status().isCreated());
        assertThat(balance(1001)).isEqualTo(INITIAL - 1_000_000);
    }

    @Test
    void unknownAccountGives404AndChangesNothing() throws Exception {
        send(UUID.randomUUID().toString(), 1001, 9999, 100).andExpect(status().isNotFound());
        send(UUID.randomUUID().toString(), 9999, 1001, 100).andExpect(status().isNotFound());
        assertUnchanged(3 * INITIAL, INITIAL, 1001);
    }

    @Test
    void missingOrInvalidIdempotencyKeyGives400() throws Exception {
        String body = body(1001, 1002, 100);
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        send("   ", 1001, 1002, 100).andExpect(status().isBadRequest());
        send("k".repeat(129), 1001, 1002, 100).andExpect(status().isBadRequest());
        send("k".repeat(128), 1001, 1002, 100).andExpect(status().isCreated());
    }

    @Test
    void malformedBodyGives400() throws Exception {
        String key = UUID.randomUUID().toString();
        for (String body : new String[]{"{}", "not json", "{\"fromAccountId\":1001,\"toAccountId\":1002}",
                "{\"fromAccountId\":1001,\"toAccountId\":1002,\"amountMinor\":\"abc\"}",
                "{\"fromAccountId\":1001,\"toAccountId\":1002,\"amountMinor\":1.5}"}) {
            mvc.perform(post("/api/transfers").header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertUnchanged(3 * INITIAL, INITIAL, 1001);
    }

    @Test
    void oppositeDirectionTransfersBothSucceed() throws Exception {
        send(UUID.randomUUID().toString(), 1001, 1002, 700).andExpect(status().isCreated());
        send(UUID.randomUUID().toString(), 1002, 1001, 300).andExpect(status().isCreated());

        assertThat(balance(1001)).isEqualTo(INITIAL - 400);
        assertThat(balance(1002)).isEqualTo(INITIAL + 400);
        assertThat(transferCount()).isEqualTo(2);
    }

    private ResultActions send(String key, long from, long to, long amount) throws Exception {
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body(from, to, amount)));
    }

    private static String body(long from, long to, long amount) {
        return "{\"fromAccountId\":%d,\"toAccountId\":%d,\"amountMinor\":%d}".formatted(from, to, amount);
    }

    /** No transfer was stored, the total is intact and the checked account still has the expected balance. */
    private void assertUnchanged(long expectedTotal, long expectedBalance, long accountId) {
        assertThat(transferCount()).isZero();
        assertThat(totalBalance()).isEqualTo(expectedTotal);
        assertThat(balance(accountId)).isEqualTo(expectedBalance);
    }

    private long balance(long id) {
        return jdbc.sql("SELECT balance_minor FROM accounts WHERE id = :id").param("id", id)
                .query(Long.class).single();
    }

    private long totalBalance() {
        return jdbc.sql("SELECT SUM(balance_minor) FROM accounts").query(Long.class).single();
    }

    private long transferCount() {
        return jdbc.sql("SELECT COUNT(*) FROM transfers").query(Long.class).single();
    }
}

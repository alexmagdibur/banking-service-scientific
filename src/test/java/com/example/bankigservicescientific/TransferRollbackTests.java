package com.example.bankigservicescientific;

import java.util.UUID;

import com.example.bankigservicescientific.transfer.Transfer;
import com.example.bankigservicescientific.transfer.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Forces a failure after the debit and credit were already executed (the transfer INSERT throws)
 * and checks in the real database that the whole transaction was rolled back.
 * Resets the test data like {@link TransferApiTests}: use a dedicated test database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransferRollbackTests {

    private static final long INITIAL = 10_000_000;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    TransferRepository transferRepository;

    @BeforeEach
    void resetTestData() {
        jdbc.sql("DELETE FROM transfers").update();
        jdbc.sql("UPDATE accounts SET balance_minor = :b WHERE id IN (1001, 1002, 1003)").param("b", INITIAL).update();
    }

    @Test
    void failureAfterBalanceUpdatesRollsBackDebitAndCredit() throws Exception {
        doThrow(new DataIntegrityViolationException("simulated failure"))
                .when(transferRepository).insert(any(Transfer.class), anyString(), anyString());

        mvc.perform(post("/api/transfers").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":1001,\"toAccountId\":1002,\"amountMinor\":5000}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));

        assertThat(balance(1001)).isEqualTo(INITIAL);
        assertThat(balance(1002)).isEqualTo(INITIAL);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transfers").query(Long.class).single()).isZero();
    }

    private long balance(long id) {
        return jdbc.sql("SELECT balance_minor FROM accounts WHERE id = :id").param("id", id)
                .query(Long.class).single();
    }
}

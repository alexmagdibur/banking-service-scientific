package com.example.bankigservicescientific;

import java.util.UUID;

import com.example.bankigservicescientific.transfer.Transfer;
import com.example.bankigservicescientific.transfer.TransferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Forces a failure after the debit and credit were already executed (the transfer INSERT throws)
 * and checks in the real database that the whole transaction was rolled back.
 */
class TransferRollbackTests extends AbstractIntegrationTest {

    @MockitoSpyBean
    TransferRepository transferRepository;

    @Test
    void failureAfterBalanceUpdatesRollsBackDebitAndCredit() throws Exception {
        doThrow(new DataIntegrityViolationException("simulated failure"))
                .when(transferRepository).insert(any(Transfer.class), anyString(), anyString());

        send(UUID.randomUUID().toString(), 1001, 1002, 5_000)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));

        assertThat(balance(1001)).isEqualTo(INITIAL);
        assertThat(balance(1002)).isEqualTo(INITIAL);
        assertThat(transferCount()).isZero();
    }
}

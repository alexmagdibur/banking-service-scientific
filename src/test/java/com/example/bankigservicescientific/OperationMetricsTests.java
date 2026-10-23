package com.example.bankigservicescientific;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.example.bankigservicescientific.account.AccountRepository;
import com.example.bankigservicescientific.transfer.Transfer;
import com.example.bankigservicescientific.transfer.TransferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** bank.operations{operation, outcome} counts every business request exactly once, by its final HTTP status. */
@ExtendWith(OutputCaptureExtension.class)
class OperationMetricsTests extends AbstractIntegrationTest {

    @Autowired
    MeterRegistry registry;

    @MockitoSpyBean
    AccountRepository accountRepository;

    @MockitoSpyBean
    TransferRepository transferRepository;

    @Test
    void balanceViewsAreCountedBySuccessAndBusinessError() throws Exception {
        long success = count("balance_view", "success");
        long business = count("balance_view", "business_error");
        long technical = count("balance_view", "technical_error");

        mvc.perform(get("/api/accounts")).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/1001")).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/9999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/abc")).andExpect(status().isBadRequest());

        assertThat(count("balance_view", "success") - success).isEqualTo(2);
        assertThat(count("balance_view", "business_error") - business).isEqualTo(2);
        assertThat(count("balance_view", "technical_error") - technical).isZero();
    }

    @Test
    void transferOutcomesAreSuccessOrBusinessError() throws Exception {
        long success = count("transfer", "success");
        long business = count("transfer", "business_error");
        long technical = count("transfer", "technical_error");
        String key = UUID.randomUUID().toString();

        send(key, 1001, 1002, 100).andExpect(status().isCreated());              // success
        send(key, 1001, 1002, 100).andExpect(status().isOk());                   // replay: success
        send(key, 1001, 1002, 200).andExpect(status().isConflict());             // key conflict
        send(UUID.randomUUID().toString(), 1001, 1002, 0).andExpect(status().isBadRequest());   // validation
        send(UUID.randomUUID().toString(), 1001, 9999, 5).andExpect(status().isNotFound());     // unknown account
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body(1001, 1002, 5)))
                .andExpect(status().isBadRequest());                              // missing Idempotency-Key
        jdbc.sql("UPDATE accounts SET balance_minor = 10 WHERE id = 1003").update();
        send(UUID.randomUUID().toString(), 1003, 1001, 11).andExpect(status().isConflict());    // insufficient funds

        assertThat(count("transfer", "success") - success).isEqualTo(2);
        assertThat(count("transfer", "business_error") - business).isEqualTo(5);
        assertThat(count("transfer", "technical_error") - technical).isZero();
    }

    @Test
    void serverFailuresAreTechnicalErrors() throws Exception {
        long balance = count("balance_view", "technical_error");
        long transfer = count("transfer", "technical_error");
        doThrow(new DataAccessResourceFailureException("simulated")).when(accountRepository).findAll();
        doThrow(new DataIntegrityViolationException("simulated"))
                .when(transferRepository).insert(any(Transfer.class), anyString(), anyString());

        mvc.perform(get("/api/accounts")).andExpect(status().isServiceUnavailable());
        send(UUID.randomUUID().toString(), 1001, 1002, 5).andExpect(status().isInternalServerError());

        assertThat(count("balance_view", "technical_error") - balance).isEqualTo(1);
        assertThat(count("transfer", "technical_error") - transfer).isEqualTo(1);
        assertThat(balance(1001)).isEqualTo(INITIAL);
    }

    @Test
    void durationIsRecorded() throws Exception {
        long nanosBefore = totalNanos("balance_view", "success");
        mvc.perform(get("/api/accounts")).andExpect(status().isOk());
        assertThat(totalNanos("balance_view", "success")).isGreaterThan(nanosBefore);
    }

    @Test
    void otherEndpointsAreNotBusinessOperations() throws Exception {
        long before = count("transfer", "success") + count("balance_view", "success");
        mvc.perform(get("/api/transfers")).andExpect(status().isOk());
        mvc.perform(get("/api/nothing")).andExpect(status().isNotFound());
        assertThat(count("transfer", "success") + count("balance_view", "success")).isEqualTo(before);
    }

    @Test
    void operationLogLineHasUtcTimeInstanceOperationOutcomeAndDurationButNoKey(CapturedOutput output) throws Exception {
        String key = "secret-idempotency-key-" + UUID.randomUUID();

        send(key, 1001, 1002, 100).andExpect(status().isCreated());
        mvc.perform(get("/api/accounts/9999")).andExpect(status().isNotFound());

        assertThat(output.getAll()).containsPattern(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\\s+INFO \\[test-instance\\] bank\\.operations : "
                        + "op=transfer outcome=success status=201 duration_ms=[0-9.]+");
        assertThat(output.getAll()).containsPattern(
                "\\[test-instance\\] bank\\.operations : op=balance_view outcome=business_error status=404 duration_ms=[0-9.]+");
        assertThat(output.getAll()).doesNotContain(key).doesNotContain("secret-idempotency-key");
    }

    private long count(String operation, String outcome) {
        return registry.get("bank.operations").tag("operation", operation).tag("outcome", outcome).timer().count();
    }

    private long totalNanos(String operation, String outcome) {
        return (long) registry.get("bank.operations").tag("operation", operation).tag("outcome", outcome)
                .timer().totalTime(TimeUnit.NANOSECONDS);
    }
}

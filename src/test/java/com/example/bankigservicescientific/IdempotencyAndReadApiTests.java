package com.example.bankigservicescientific;

import java.util.UUID;

import tools.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idempotency of POST /api/transfers and the read endpoints, checked against the real PostgreSQL.
 * Resets the test data before every test: use a dedicated test database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyAndReadApiTests {

    private static final long INITIAL = 10_000_000;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    tools.jackson.databind.json.JsonMapper json;

    @BeforeEach
    void resetTestData() {
        jdbc.sql("DELETE FROM transfers").update();
        jdbc.sql("UPDATE accounts SET balance_minor = :b WHERE id IN (1001, 1002, 1003)").param("b", INITIAL).update();
    }

    @Test
    void exactRepeatReturnsSameTransferWith200AndDebitsOnce() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = send(key, 1001, 1002, 1_000).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        String second = send(key, 1001, 1002, 1_000).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();

        assertThat(json.readTree(second)).isEqualTo(json.readTree(first));
        assertThat(balance(1001)).isEqualTo(INITIAL - 1_000);
        assertThat(balance(1002)).isEqualTo(INITIAL + 1_000);
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    void repeatManyTimesStillOneTransfer() throws Exception {
        String key = UUID.randomUUID().toString();
        send(key, 1002, 1003, 300).andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) {
            send(key, 1002, 1003, 300).andExpect(status().isOk());
        }
        assertThat(balance(1002)).isEqualTo(INITIAL - 300);
        assertThat(balance(1003)).isEqualTo(INITIAL + 300);
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentContentGives409AndChangesNothingMore() throws Exception {
        String key = UUID.randomUUID().toString();
        send(key, 1001, 1002, 1_000).andExpect(status().isCreated());

        for (long[] other : new long[][]{{1001, 1002, 2_000}, {1001, 1003, 1_000}, {1002, 1001, 1_000}}) {
            send(key, other[0], other[1], other[2])
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        }

        assertThat(balance(1001)).isEqualTo(INITIAL - 1_000);
        assertThat(balance(1002)).isEqualTo(INITIAL + 1_000);
        assertThat(balance(1003)).isEqualTo(INITIAL);
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    void replayStillWorksAfterTheSenderBecameUnableToPay() throws Exception {
        String key = UUID.randomUUID().toString();
        send(key, 1003, 1001, 400).andExpect(status().isCreated());
        jdbc.sql("UPDATE accounts SET balance_minor = 0 WHERE id = 1003").update();

        send(key, 1003, 1001, 400).andExpect(status().isOk());

        assertThat(balance(1003)).isZero();
        assertThat(transferCount()).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotConsumeTheKey() throws Exception {
        String key = UUID.randomUUID().toString();
        jdbc.sql("UPDATE accounts SET balance_minor = 100 WHERE id = 1003").update();

        send(key, 1003, 1001, 500).andExpect(status().isConflict());
        assertThat(transferCount()).isZero();

        jdbc.sql("UPDATE accounts SET balance_minor = 1000 WHERE id = 1003").update();
        send(key, 1003, 1001, 500).andExpect(status().isCreated());
        assertThat(balance(1003)).isEqualTo(500);
    }

    @Test
    void getTransferByIdReturnsTheSameData() throws Exception {
        String created = send(UUID.randomUUID().toString(), 1001, 1002, 777).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode expected = json.readTree(created);

        String fetched = mvc.perform(get("/api/transfers/" + expected.get("id").asString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(fetched)).isEqualTo(expected);
    }

    @Test
    void getUnknownTransferGives404AndMalformedIdGives400() throws Exception {
        mvc.perform(get("/api/transfers/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        mvc.perform(get("/api/transfers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void listIsNewestFirstAndDefaultsToTwenty() throws Exception {
        mvc.perform(get("/api/transfers")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        for (int i = 1; i <= 25; i++) {
            send("list-" + i, 1001, 1002, i).andExpect(status().isCreated());
        }

        mvc.perform(get("/api/transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].amountMinor").value(25))
                .andExpect(jsonPath("$[19].amountMinor").value(6));
        mvc.perform(get("/api/transfers?limit=3"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].amountMinor").value(25))
                .andExpect(jsonPath("$[2].amountMinor").value(23));
        mvc.perform(get("/api/transfers?limit=1")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/transfers?limit=100")).andExpect(jsonPath("$.length()").value(25));
    }

    @Test
    void limitOutOfRangeOrNotANumberGives400() throws Exception {
        for (String limit : new String[]{"0", "-1", "101", "abc", "1.5"}) {
            mvc.perform(get("/api/transfers?limit=" + limit))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    private ResultActions send(String key, long from, long to, long amount) throws Exception {
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amountMinor\":%d}".formatted(from, to, amount)));
    }

    private long balance(long id) {
        return jdbc.sql("SELECT balance_minor FROM accounts WHERE id = :id").param("id", id)
                .query(Long.class).single();
    }

    private long transferCount() {
        return jdbc.sql("SELECT COUNT(*) FROM transfers").query(Long.class).single();
    }
}

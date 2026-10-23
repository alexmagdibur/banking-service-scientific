package com.example.bankigservicescientific;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base class of the integration tests that run against a real PostgreSQL.
 * Before every test the transfers table is emptied and the three test accounts are reset to
 * {@link #INITIAL} kopecks, so the tests refuse to run on a database whose name does not end with "_test".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {

    static final long INITIAL = 10_000_000;
    static final long[] ACCOUNTS = {1001, 1002, 1003};

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetTestData() {
        String database = jdbc.sql("SELECT current_database()").query(String.class).single();
        assertThat(database)
                .as("integration tests wipe data and may only run on a dedicated test database")
                .endsWith("_test");
        jdbc.sql("DELETE FROM transfers").update();
        jdbc.sql("UPDATE accounts SET balance_minor = :b WHERE id IN (1001, 1002, 1003)").param("b", INITIAL).update();
    }

    protected ResultActions send(String key, long from, long to, long amount) throws Exception {
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body(from, to, amount)));
    }

    protected static String body(long from, long to, long amount) {
        return "{\"fromAccountId\":%d,\"toAccountId\":%d,\"amountMinor\":%d}".formatted(from, to, amount);
    }

    protected long balance(long id) {
        return jdbc.sql("SELECT balance_minor FROM accounts WHERE id = :id").param("id", id)
                .query(Long.class).single();
    }

    protected long totalBalance() {
        return jdbc.sql("SELECT SUM(balance_minor) FROM accounts").query(Long.class).single();
    }

    protected long transferCount() {
        return jdbc.sql("SELECT COUNT(*) FROM transfers").query(Long.class).single();
    }

    /** HTTP status and body of one response. */
    protected record Response(int status, String body) {
    }

    /**
     * Runs all tasks at the same moment on separate threads and returns their results in task order.
     * A task that does not finish within 60 seconds (e.g. because of a deadlock) fails the test.
     */
    protected <T> List<T> concurrently(List<Callable<T>> tasks, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    protected Callable<Response> request(String key, long from, long to, long amount) {
        return () -> {
            var result = send(key, from, to, amount).andReturn().getResponse();
            return new Response(result.getStatus(), result.getContentAsString());
        };
    }

    /** Every balance is non-negative, the total is unchanged and the transfer rows explain every balance. */
    protected void assertBankInvariants() {
        assertThat(totalBalance()).isEqualTo(ACCOUNTS.length * INITIAL);
        for (long id : ACCOUNTS) {
            assertThat(balance(id)).isGreaterThanOrEqualTo(0);
            long received = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM transfers WHERE to_account_id = :id")
                    .param("id", id).query(Long.class).single();
            long sent = jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM transfers WHERE from_account_id = :id")
                    .param("id", id).query(Long.class).single();
            assertThat(balance(id)).as("balance of %d must equal initial + received - sent", id)
                    .isEqualTo(INITIAL + received - sent);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) - COUNT(DISTINCT idempotency_key) FROM transfers")
                .query(Long.class).single()).isZero();
    }
}

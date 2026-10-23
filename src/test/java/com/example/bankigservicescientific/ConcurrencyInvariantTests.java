package com.example.bankigservicescientific;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent requests against the real PostgreSQL. Each test releases all requests at the same moment
 * and then checks the database: no lost update, no double debit, no negative balance, unchanged total.
 */
class ConcurrencyInvariantTests extends AbstractIntegrationTest {

    @Autowired
    JsonMapper json;

    @Test
    void manyConcurrentTransfersKeepEveryInvariant() throws Exception {
        Random random = new Random(42);
        long[] expected = {INITIAL, INITIAL, INITIAL};
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            int from = random.nextInt(3);
            int to = (from + 1 + random.nextInt(2)) % 3;
            long amount = 1 + random.nextInt(1_000);
            expected[from] -= amount;
            expected[to] += amount;
            tasks.add(request(UUID.randomUUID().toString(), ACCOUNTS[from], ACCOUNTS[to], amount));
        }

        List<Response> responses = concurrently(tasks, 16);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(transferCount()).isEqualTo(300);
        for (int i = 0; i < 3; i++) {
            assertThat(balance(ACCOUNTS[i])).as("account %d (no lost update)", ACCOUNTS[i]).isEqualTo(expected[i]);
        }
        assertBankInvariants();
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(request(UUID.randomUUID().toString(), 1001, 1002, 10));
            tasks.add(request(UUID.randomUUID().toString(), 1002, 1001, 10));
        }

        List<Response> responses = concurrently(tasks, 16);

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(balance(1001)).isEqualTo(INITIAL);
        assertThat(balance(1002)).isEqualTo(INITIAL);
        assertThat(transferCount()).isEqualTo(200);
        assertBankInvariants();
    }

    @Test
    void competingForTheLastMoneyNeverOverdrawsTheAccount() throws Exception {
        jdbc.sql("UPDATE accounts SET balance_minor = 5000 WHERE id = 1003").update();
        jdbc.sql("UPDATE accounts SET balance_minor = :b WHERE id = 1001").param("b", INITIAL + INITIAL - 5000).update();
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(request(UUID.randomUUID().toString(), 1003, i % 2 == 0 ? 1001 : 1002, 1_000));
        }

        List<Response> responses = concurrently(tasks, 16);

        long created = responses.stream().filter(r -> r.status() == 201).count();
        long refused = responses.stream().filter(r -> r.status() == 409).count();
        assertThat(created).isEqualTo(5);
        assertThat(refused).isEqualTo(25);
        assertThat(balance(1003)).isZero();
        assertThat(transferCount()).isEqualTo(5);
        assertThat(totalBalance()).isEqualTo(3 * INITIAL);
    }

    @Test
    void concurrentRepeatsOfOneKeyCreateExactlyOneTransfer() throws Exception {
        String key = UUID.randomUUID().toString();
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(request(key, 1001, 1002, 2_500));
        }

        List<Response> responses = concurrently(tasks, 16);

        assertThat(responses.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.status() == 200).count()).isEqualTo(29);
        assertThat(responses.stream().map(r -> json.readTree(r.body()).get("id").asString()).distinct())
                .hasSize(1);
        assertThat(balance(1001)).isEqualTo(INITIAL - 2_500);
        assertThat(balance(1002)).isEqualTo(INITIAL + 2_500);
        assertThat(transferCount()).isEqualTo(1);
        assertBankInvariants();
    }

    @Test
    void concurrentRequestsWithOneKeyAndDifferentContentLetOnlyOneWin() throws Exception {
        String key = UUID.randomUUID().toString();
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(request(key, 1002, 1003, i % 2 == 0 ? 100 : 101));
        }

        List<Response> responses = concurrently(tasks, 16);

        List<Response> created = responses.stream().filter(r -> r.status() == 201).toList();
        assertThat(created).hasSize(1);
        var winner = json.readTree(created.get(0).body());
        long winnerAmount = winner.get("amountMinor").asLong();
        for (Response r : responses) {
            if (r.status() == 200) {
                assertThat(json.readTree(r.body())).isEqualTo(winner);
            } else if (r.status() != 201) {
                assertThat(r.status()).isEqualTo(409);
            }
        }
        assertThat(transferCount()).isEqualTo(1);
        assertThat(balance(1002)).isEqualTo(INITIAL - winnerAmount);
        assertThat(balance(1003)).isEqualTo(INITIAL + winnerAmount);
        assertBankInvariants();
    }

    @Test
    void concurrentRepeatsOfManyKeysEachCreateOneTransfer() throws Exception {
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            String key = "multi-" + k;
            for (int repeat = 0; repeat < 5; repeat++) {
                tasks.add(request(key, ACCOUNTS[k % 3], ACCOUNTS[(k + 1) % 3], 10 + k));
            }
        }

        List<Response> responses = concurrently(tasks, 16);

        assertThat(responses.stream().filter(r -> r.status() == 201).count()).isEqualTo(20);
        assertThat(responses.stream().filter(r -> r.status() == 200).count()).isEqualTo(80);
        assertThat(transferCount()).isEqualTo(20);
        assertBankInvariants();
    }
}

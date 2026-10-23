package com.example.bankigservicescientific;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Profile "research": the simulated unavailability of one instance, controlled through the management port.
 * The tests only read the bank tables; the point is that a request swallowed by the fault never executes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "research"})
@AutoConfigureMetrics
class ResearchFaultTests {

    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(1);

    @LocalServerPort
    int mainPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void unresponsiveInstanceHangsApiFailsProbesKeepsControlAndRecoversWithoutChangingData() throws Exception {
        long transfersBefore = transferCount();
        long totalBefore = totalBalance();
        String key = "research-fault-" + UUID.randomUUID();
        assertThat(get(mainPort, "/api/accounts").statusCode()).isEqualTo(200);
        assertThat(get(mainPort, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(mainPort, "/readyz").statusCode()).isEqualTo(200);

        long started = System.nanoTime();
        HttpResponse<String> confirmation = post(managementPort, "/actuator/researchfault?mode=UNRESPONSIVE&durationSeconds=4", null);
        assertThat(confirmation.statusCode()).isEqualTo(200);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(confirmation.body()).contains("\"instanceId\":\"test-instance\"", "\"mode\":\"UNRESPONSIVE\"",
                "\"durationSeconds\":4", "\"startsAt\"", "\"endsAt\"");

        Thread.sleep(700); // the window starts shortly after the confirmation

        assertThatThrownBy(() -> getWithTimeout(mainPort, "/api/accounts")).isInstanceOf(HttpTimeoutException.class);
        assertThatThrownBy(() -> postTransferWithTimeout(key)).isInstanceOf(HttpTimeoutException.class);
        assertThat(get(mainPort, "/livez").statusCode()).isEqualTo(503);
        assertThat(get(mainPort, "/readyz").statusCode()).isEqualTo(503);
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(503);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(503);
        // control and observability stay available on the management port
        HttpResponse<String> status = get(managementPort, "/actuator/researchfault");
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("\"active\":true", "\"instanceId\":\"test-instance\"");
        assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
        assertThat(get(mainPort, "/").statusCode()).isEqualTo(200); // only the API is affected

        awaitStatus("/livez", 200, Duration.ofSeconds(8));

        assertThat(get(mainPort, "/readyz").statusCode()).isEqualTo(200);
        assertThat(get(mainPort, "/api/accounts").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/researchfault").body()).contains("\"active\":false");
        Thread.sleep(500); // the swallowed requests were released: they must not have been executed
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transfers WHERE idempotency_key = :k").param("k", key)
                .query(Long.class).single()).isZero();
        assertThat(transferCount()).isEqualTo(transfersBefore);
        assertThat(totalBalance()).isEqualTo(totalBefore);
    }

    @Test
    void invalidRequestsAreRejectedAndDoNotActivateTheFault() throws Exception {
        String[] queries = {"", "?mode=UNRESPONSIVE", "?durationSeconds=5", "?mode=BROKEN&durationSeconds=5",
                "?mode=unresponsive&durationSeconds=5", "?mode=UNRESPONSIVE&durationSeconds=0",
                "?mode=UNRESPONSIVE&durationSeconds=121", "?mode=UNRESPONSIVE&durationSeconds=-5",
                "?mode=UNRESPONSIVE&durationSeconds=abc"};
        for (String query : queries) {
            assertThat(post(managementPort, "/actuator/researchfault" + query, null).statusCode())
                    .as("POST /actuator/researchfault%s", query).isEqualTo(400);
        }
        Thread.sleep(500);
        assertThat(get(mainPort, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(mainPort, "/api/accounts").statusCode()).isEqualTo(200);
    }

    @Test
    void limitsOfTheDurationAreAccepted() throws Exception {
        assertThat(post(managementPort, "/actuator/researchfault?mode=UNRESPONSIVE&durationSeconds=1", null).statusCode())
                .isEqualTo(200);
        awaitStatus("/livez", 200, Duration.ofSeconds(5));
        assertThat(post(managementPort, "/actuator/researchfault?mode=UNRESPONSIVE&durationSeconds=120", null).statusCode())
                .isEqualTo(200);
        assertThat(delete(managementPort, "/actuator/researchfault").statusCode()).isEqualTo(200);
    }

    @Test
    void jsonBodyIsAcceptedToo() throws Exception {
        HttpResponse<String> response = post(managementPort, "/actuator/researchfault",
                "{\"mode\":\"UNRESPONSIVE\",\"durationSeconds\":1}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"durationSeconds\":1");
        awaitStatus("/livez", 200, Duration.ofSeconds(5));
    }

    @Test
    void faultCanBeClearedBeforeItEnds() throws Exception {
        post(managementPort, "/actuator/researchfault?mode=UNRESPONSIVE&durationSeconds=60", null);
        Thread.sleep(600);
        assertThat(get(mainPort, "/livez").statusCode()).isEqualTo(503);

        HttpResponse<String> cleared = delete(managementPort, "/actuator/researchfault");

        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(cleared.body()).contains("\"active\":false");
        assertThat(get(mainPort, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(mainPort, "/api/accounts").statusCode()).isEqualTo(200);
    }

    private void awaitStatus(String path, int expected, Duration atMost) throws Exception {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (System.nanoTime() < deadline) {
            if (get(mainPort, path).statusCode() == expected) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(get(mainPort, path).statusCode()).isEqualTo(expected);
    }

    private long transferCount() {
        return jdbc.sql("SELECT COUNT(*) FROM transfers").query(Long.class).single();
    }

    private long totalBalance() {
        return jdbc.sql("SELECT SUM(balance_minor) FROM accounts").query(Long.class).single();
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(port, path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithTimeout(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(port, path)).timeout(CLIENT_TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postTransferWithTimeout(String key) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(mainPort, "/api/transfers")).timeout(CLIENT_TIMEOUT)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"fromAccountId\":1001,\"toAccountId\":1002,\"amountMinor\":1}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(port, path));
        // Actuator write operations accept only application/json (even when the parameters are in the query).
        builder.header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(port, path)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }
}

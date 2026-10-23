package com.example.bankigservicescientific;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The scrape endpoint on the management port: standard metrics, bounded business metrics, no identifiers in labels. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@ActiveProfiles("test")
class PrometheusEndpointTests {

    @LocalServerPort
    int mainPort;

    @LocalManagementPort
    int managementPort;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void prometheusIsServedOnTheManagementPortOnly() throws Exception {
        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type").orElse("")).contains("text/plain");
        assertThat(get(mainPort, "/actuator/prometheus").statusCode()).isEqualTo(404);
    }

    @Test
    void standardJvmProcessAndHttpMetricsArePresent() throws Exception {
        get(mainPort, "/api/accounts");

        String scrape = get(managementPort, "/actuator/prometheus").body();

        assertThat(scrape).contains("jvm_memory_used_bytes", "jvm_threads_live_threads", "jvm_gc_",
                "process_cpu_usage", "process_uptime_seconds", "system_cpu_usage", "http_server_requests_seconds_count",
                "http_server_requests_seconds_bucket", "hikaricp_connections_active", "application=\"bank-api\"");
    }

    @Test
    void businessMetricsHaveOnlyOperationAndOutcomeLabels() throws Exception {
        get(mainPort, "/api/accounts");
        get(mainPort, "/api/accounts/1001");
        get(mainPort, "/api/accounts/9999");

        String scrape = get(managementPort, "/actuator/prometheus").body();

        for (String operation : new String[]{"balance_view", "transfer"}) {
            for (String outcome : new String[]{"success", "business_error", "technical_error"}) {
                assertThat(scrape).as("series %s/%s exists from the first scrape", operation, outcome)
                        .containsPattern("bank_operations_seconds_count\\{[^}]*operation=\"" + operation
                                + "\"[^}]*outcome=\"" + outcome + "\"[^}]*}");
            }
        }
        Set<String> labelNames = new HashSet<>();
        for (String line : scrape.split("\n")) {
            if (line.startsWith("bank_operations")) {
                Matcher labels = Pattern.compile("(\\w+)=\"").matcher(line.substring(line.indexOf('{'), line.indexOf('}')));
                while (labels.find()) {
                    labelNames.add(labels.group(1));
                }
            }
        }
        assertThat(labelNames).containsExactlyInAnyOrder("application", "operation", "outcome");
    }

    @Test
    void identifiersNeverBecomeLabelValues() throws Exception {
        get(mainPort, "/api/accounts/1001");
        get(mainPort, "/api/accounts/9999");
        get(mainPort, "/api/transfers/00000000-0000-0000-0000-000000000000");

        String scrape = get(managementPort, "/actuator/prometheus").body();

        assertThat(scrape).contains("uri=\"/api/accounts/{id}\"").contains("uri=\"/api/transfers/{id}\"");
        assertThat(scrape).doesNotContain("/api/accounts/1001").doesNotContain("/api/accounts/9999")
                .doesNotContain("00000000-0000-0000-0000-000000000000")
                .doesNotContainIgnoringCase("accountId").doesNotContainIgnoringCase("transferId")
                .doesNotContainIgnoringCase("idempotency");
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

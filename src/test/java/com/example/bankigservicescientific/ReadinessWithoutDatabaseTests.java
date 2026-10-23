package com.example.bankigservicescientific;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/** When PostgreSQL is unreachable the instance leaves the Service (readiness DOWN) but is not restarted (liveness UP). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReadinessWithoutDatabaseTests {

    @LocalServerPort
    int mainPort;

    @MockitoSpyBean
    DataSource dataSource;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void readinessGoesDownAndLivenessStaysUpWhileTheDatabaseIsUnavailable() throws Exception {
        assertThat(get("/readyz").statusCode()).isEqualTo(200);

        doThrow(new SQLException("simulated: database unreachable")).when(dataSource).getConnection();
        try {
            assertThat(get("/readyz").statusCode()).isEqualTo(503);
            assertThat(get("/livez").statusCode()).isEqualTo(200);
            HttpResponse<String> api = get("/api/accounts");
            assertThat(api.statusCode()).isEqualTo(503);
            assertThat(api.body()).contains("SERVICE_UNAVAILABLE").doesNotContain("simulated");
        } finally {
            reset(dataSource);
        }

        assertThat(get("/readyz").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + mainPort + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

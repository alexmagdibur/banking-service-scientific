package com.example.bankigservicescientific;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.example.bankigservicescientific.research.ResearchFaultEndpoint;
import com.example.bankigservicescientific.research.ResearchFaultFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP against both ports: the user port (random here, 8080 normally) must serve /livez and /readyz,
 * the management port (random here, 8081 normally) must carry /actuator and nothing else must leak onto 8080.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HealthProbesTests {

    @LocalServerPort
    int mainPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    HealthEndpointGroups groups;

    @Autowired
    ApplicationContext context;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void livenessAndReadinessAreServedOnTheMainPort() throws Exception {
        HttpResponse<String> live = get(mainPort, "/livez");
        HttpResponse<String> ready = get(mainPort, "/readyz");

        assertThat(live.statusCode()).isEqualTo(200);
        assertThat(live.body()).contains("\"status\":\"UP\"");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void actuatorLivesOnTheManagementPortOnly() throws Exception {
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get(mainPort, "/actuator/health").statusCode()).isEqualTo(404);
        assertThat(get(mainPort, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(404);
    }

    @Test
    void livenessDoesNotDependOnTheDatabaseButReadinessDoes() {
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("db")).isTrue();
    }

    @Test
    void researchFaultEndpointDoesNotExistWithoutTheResearchProfile() throws Exception {
        assertThat(context.getBeanNamesForType(ResearchFaultEndpoint.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ResearchFaultFilter.class)).isEmpty();
        assertThat(post(managementPort, "/actuator/researchfault?mode=UNRESPONSIVE&durationSeconds=5").statusCode())
                .isEqualTo(404);
        assertThat(get(managementPort, "/actuator/researchfault").statusCode()).isEqualTo(404);
        assertThat(post(mainPort, "/actuator/researchfault?mode=UNRESPONSIVE&durationSeconds=5").statusCode())
                .isEqualTo(404);
        assertThat(get(mainPort, "/api/accounts").statusCode()).isEqualTo(200);
        assertThat(get(mainPort, "/livez").statusCode()).isEqualTo(200);
    }

    @Test
    void healthDetailsAreNotExposed() throws Exception {
        assertThat(get(managementPort, "/actuator/health").body()).doesNotContain("PostgreSQL").doesNotContain("jdbc");
        assertThat(get(mainPort, "/readyz").body()).doesNotContain("PostgreSQL").doesNotContain("jdbc");
    }

    @Test
    void everyResponseOfTheMainPortCarriesTheInstanceId() throws Exception {
        for (String path : new String[]{"/api/accounts", "/api/accounts/9999", "/livez", "/readyz", "/"}) {
            assertThat(get(mainPort, path).headers().firstValue("X-Instance-Id"))
                    .as("X-Instance-Id on %s", path).contains("test-instance");
        }
    }

    private HttpResponse<String> post(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

package com.system.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Base class for integration tests: real HTTP against the app on a random port,
 * backed by one PostgreSQL 16 container migrated by the real Flyway scripts.
 *
 * The container is static and started once, so every subclass shares it, and
 * Spring caches the application context across subclasses because their
 * configuration is identical.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    // HTTP/1.1 so the client never attempts an h2c upgrade against Tomcat
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper mapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE job_attempts, jobs");
    }

    /** Sends the body exactly as given, so tests can send malformed JSON. */
    protected HttpResponse<String> postJob(String body, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/jobs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected HttpResponse<String> postJob(String body) {
        return postJob(body, null);
    }

    /** GET /api/jobs with a raw query string, e.g. "status=PENDING&limit=3", or "" for none. */
    protected HttpResponse<String> listJobs(String query) {
        String uri = "http://localhost:" + port + "/api/jobs" + (query.isEmpty() ? "" : "?" + query);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(uri)).GET().build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected HttpResponse<String> cancelJob(String id) {
        return postJobAction(id, "cancel");
    }

    protected HttpResponse<String> requeueJob(String id) {
        return postJobAction(id, "requeue");
    }

    /** POST /api/jobs/{id}/{action}. Takes the id as a string so tests can also send malformed ids. */
    private HttpResponse<String> postJobAction(String id, String action) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/jobs/" + id + "/" + action))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected JsonNode json(HttpResponse<String> response) {
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected int jobCount() {
        return jdbc.queryForObject("SELECT count(*) FROM jobs", Integer.class);
    }
}

package com.quickpaste.controller;

import com.quickpaste.config.QuickPasteConfig;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

/**
 * Liveness + readiness (SPEC §12.1): DB reachable, storage dir writable.
 * Unauthenticated by design (outside /api).
 */
@Controller("/health")
@ExecuteOn(TaskExecutors.BLOCKING)
public class HealthController {

    private final DataSource dataSource;
    private final QuickPasteConfig config;

    public HealthController(DataSource dataSource, QuickPasteConfig config) {
        // Unwrap Micronaut Data's transaction-aware delegate: the health probe runs
        // outside any transaction and needs a raw connection.
        this.dataSource = io.micronaut.data.connection.jdbc.advice.DelegatingDataSource.unwrapDataSource(dataSource);
        this.config = config;
    }

    @Get
    public HttpResponse<Map<String, Object>> health() {
        String db = checkDb();
        String storage = checkStorage();
        boolean up = "up".equals(db) && "up".equals(storage);
        Map<String, Object> body = Map.of(
                "status", up ? "up" : "down",
                "checks", Map.of("db", db, "storage", storage));
        return up ? HttpResponse.ok(body)
                : HttpResponse.<Map<String, Object>>status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private String checkDb() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return "up";
        } catch (Exception e) {
            return "down";
        }
    }

    private String checkStorage() {
        try {
            Path root = Path.of(config.getStorageDir());
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, ".health", ".probe");
            Files.deleteIfExists(probe);
            return "up";
        } catch (Exception e) {
            return "down";
        }
    }
}

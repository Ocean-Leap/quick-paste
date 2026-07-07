package com.quickpaste;

import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared PostgreSQL Testcontainer (started once per JVM) + per-class blob storage dir.
 * The pgvector image matches production so the vector extension in V1 is exercised.
 * Test classes share the database, so fixtures must use unique emails.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class DbTestBase implements TestPropertyProvider {

    @SuppressWarnings("resource") // lives for the whole test JVM
    protected static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static {
        DB.start();
    }

    protected final Path storageDir;

    protected DbTestBase() {
        try {
            this.storageDir = Files.createTempDirectory(
                    Path.of("build").toAbsolutePath(), "qp-storage-" + getClass().getSimpleName() + "-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = new HashMap<>();
        props.put("micronaut.server.port", "-1"); // random port; classes run in one JVM
        props.put("datasources.default.url", DB.getJdbcUrl());
        props.put("datasources.default.username", DB.getUsername());
        props.put("datasources.default.password", DB.getPassword());
        props.put("quickpaste.storage-dir", storageDir.toString());
        props.put("quickpaste.session-secret", "test-session-secret");
        props.put("quickpaste.allowed-emails", "admin@test.local");
        props.put("quickpaste.dev-auth-bypass", "false");
        return props;
    }
}

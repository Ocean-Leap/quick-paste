package com.quickpaste.repository;

import com.quickpaste.model.AuthSession;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface AuthSessionRepository extends CrudRepository<AuthSession, String> {

    @Query("UPDATE sessions SET revoked_at = :at WHERE id = :id")
    void revoke(String id, Instant at);

    @Query("UPDATE sessions SET revoked_at = :at WHERE user_id = :userId AND revoked_at IS NULL")
    void revokeAllForUser(String userId, Instant at);

    @Query("DELETE FROM sessions WHERE expires_at < :now")
    long deleteExpired(Instant now);
}

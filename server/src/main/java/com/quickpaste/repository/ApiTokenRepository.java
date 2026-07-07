package com.quickpaste.repository;

import com.quickpaste.model.ApiToken;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ApiTokenRepository extends CrudRepository<ApiToken, String> {

    List<ApiToken> findByPrefix(String prefix);

    List<ApiToken> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("UPDATE api_tokens SET last_used_at = :at WHERE id = :id")
    void touchLastUsed(String id, Instant at);

    @Query("UPDATE api_tokens SET revoked_at = :at WHERE id = :id")
    void revoke(String id, Instant at);

    @Query("UPDATE api_tokens SET revoked_at = :at WHERE user_id = :userId AND revoked_at IS NULL")
    void revokeAllForUser(String userId, Instant at);
}

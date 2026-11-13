package com.quickpaste.repository;

import com.quickpaste.model.ClipDelivery;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ClipDeliveryRepository extends CrudRepository<ClipDelivery, String> {

    Optional<ClipDelivery> findByClipId(String clipId);

    @Query("""
            SELECT * FROM clip_deliveries
            WHERE status = 'pending'
              AND next_attempt_at <= :now
              AND attempts < :maxAttempts
            ORDER BY next_attempt_at ASC
            LIMIT :limit
            """)
    List<ClipDelivery> findDue(Instant now, int maxAttempts, int limit);

    /**
     * Optimistic claim: moves {@code next_attempt_at} forward so a concurrent poll
     * or enqueue won't pick up the same row. Returns the updated row count (0 = lost race).
     */
    @Query("""
            UPDATE clip_deliveries
            SET next_attempt_at = :leaseUntil, updated_at = :now
            WHERE clip_id = :clipId
              AND status = 'pending'
              AND next_attempt_at <= :now
            """)
    int claimDue(String clipId, Instant now, Instant leaseUntil);

    @Query("SELECT * FROM clip_deliveries WHERE clip_id IN (:clipIds)")
    List<ClipDelivery> findByClipIds(List<String> clipIds);
}
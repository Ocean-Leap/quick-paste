package com.quickpaste.repository;

import com.quickpaste.model.ExportJob;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ExportJobRepository extends CrudRepository<ExportJob, String> {

    Optional<ExportJob> findByIdAndUserId(String id, String userId);

    List<ExportJob> findByUserId(String userId);

    @Query("SELECT * FROM export_jobs WHERE expires_at IS NOT NULL AND expires_at < :now")
    List<ExportJob> findExpired(Instant now);

    @Query("UPDATE export_jobs SET status = :status, storage_path = :storagePath, expires_at = :expiresAt WHERE id = :id")
    void updateResult(String id, String status, @Nullable String storagePath, @Nullable Instant expiresAt);
}

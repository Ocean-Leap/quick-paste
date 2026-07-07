package com.quickpaste.repository;

import com.quickpaste.model.UploadSession;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UploadSessionRepository extends CrudRepository<UploadSession, String> {

    Optional<UploadSession> findByIdAndChannelId(String id, String channelId);

    @Query("SELECT * FROM upload_sessions WHERE updated_at < :cutoff")
    List<UploadSession> findIdleBefore(Instant cutoff);

    @Query("UPDATE upload_sessions SET updated_at = :at WHERE id = :id")
    void touch(String id, Instant at);
}

package com.quickpaste.repository;

import com.quickpaste.model.StoredFile;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface StoredFileRepository extends CrudRepository<StoredFile, String> {

    Optional<StoredFile> findByIdAndChannelId(String id, String channelId);

    List<StoredFile> findByChannelId(String channelId);

    List<StoredFile> findByCreatedBy(String createdBy);

    /** Per-user storage quota basis (FR-43). */
    @Query("SELECT COALESCE(SUM(size), 0) FROM files WHERE created_by = :userId")
    long totalSizeByUser(String userId);

    @Query("SELECT * FROM files WHERE expires_at < :now")
    List<StoredFile> findExpired(Instant now);

    /** Uploads never referenced by a clip, past the abandoned-upload GC window. */
    @Query("SELECT f.* FROM files f LEFT JOIN clips c ON c.file_id = f.id WHERE c.id IS NULL AND f.created_at < :cutoff")
    List<StoredFile> findUnreferencedBefore(Instant cutoff);
}

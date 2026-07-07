package com.quickpaste.repository;

import com.quickpaste.model.Clip;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ClipRepository extends CrudRepository<Clip, String> {

    Optional<Clip> findByIdAndChannelId(String id, String channelId);

    @Query("SELECT * FROM clips WHERE channel_id = :channelId AND expires_at > :now ORDER BY id DESC LIMIT 1")
    Optional<Clip> findLatest(String channelId, Instant now);

    /** WS catch-up: ULID-cursor, ascending, gap-free (SPEC §8.5). Capped defensively at 500. */
    @Query("SELECT * FROM clips WHERE channel_id = :channelId AND id > :afterId AND expires_at > :now ORDER BY id ASC LIMIT 500")
    List<Clip> findCatchupAfter(String channelId, String afterId, Instant now);

    @Query("SELECT * FROM clips WHERE channel_id = :channelId AND expires_at > :now ORDER BY id ASC LIMIT 500")
    List<Clip> findCatchupAll(String channelId, Instant now);

    /** History paging, newest-first (SPEC §8.4). CASTs let Postgres type the
     *  nullable bind params in the IS NULL guards. */
    @Query("SELECT * FROM clips WHERE channel_id = :channelId AND expires_at > :now"
            + " AND (CAST(:beforeId AS TEXT) IS NULL OR id < :beforeId)"
            + " AND (CAST(:type AS TEXT) IS NULL OR type = :type)"
            + " ORDER BY id DESC LIMIT :limit")
    List<Clip> listBefore(String channelId, Instant now, @Nullable String beforeId, @Nullable String type, int limit);

    /** Catch-up paging, oldest-first after a cursor (SPEC §8.4). */
    @Query("SELECT * FROM clips WHERE channel_id = :channelId AND expires_at > :now"
            + " AND id > :afterId"
            + " AND (CAST(:type AS TEXT) IS NULL OR type = :type)"
            + " ORDER BY id ASC LIMIT :limit")
    List<Clip> listAfter(String channelId, Instant now, String afterId, @Nullable String type, int limit);

    /**
     * Echo-loop dedup (FR-42): same (channel, user, origin, hash) within the window.
     * Scoped to the originating user — device labels are not unique across users.
     */
    @Query("SELECT * FROM clips WHERE channel_id = :channelId AND origin_user_id = :originUserId"
            + " AND origin_device = :originDevice AND content_hash = :contentHash"
            + " AND created_at > :cutoff ORDER BY id DESC LIMIT 1")
    Optional<Clip> findRecentDuplicate(String channelId, String originUserId, String originDevice,
                                       String contentHash, Instant cutoff);

    long countByChannelIdAndFileId(String channelId, String fileId);

    @Query("SELECT * FROM clips WHERE channel_id = :channelId ORDER BY id ASC")
    List<Clip> findAllByChannel(String channelId);

    @Query("DELETE FROM clips WHERE channel_id = :channelId")
    long deleteByChannel(String channelId);

    @Query("DELETE FROM clips WHERE expires_at < :now")
    long deleteExpired(Instant now);
}

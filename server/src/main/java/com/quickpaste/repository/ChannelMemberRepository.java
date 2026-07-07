package com.quickpaste.repository;

import com.quickpaste.model.ChannelMember;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ChannelMemberRepository extends CrudRepository<ChannelMember, String> {

    Optional<ChannelMember> findByChannelIdAndUserId(String channelId, String userId);

    Optional<ChannelMember> findByChannelIdAndInvitedEmail(String channelId, String invitedEmail);

    List<ChannelMember> findByChannelId(String channelId);

    List<ChannelMember> findByUserIdAndStatus(String userId, String status);

    List<ChannelMember> findByInvitedEmailAndStatus(String invitedEmail, String status);

    long countByChannelId(String channelId);

    @Query("UPDATE channel_members SET user_id = :userId, status = 'active', joined_at = :at WHERE id = :id")
    void activate(String id, String userId, Instant at);
}

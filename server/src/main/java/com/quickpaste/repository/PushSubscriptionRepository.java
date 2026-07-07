package com.quickpaste.repository;

import com.quickpaste.model.PushSubscription;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface PushSubscriptionRepository extends CrudRepository<PushSubscription, String> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    List<PushSubscription> findByUserId(String userId);

    /** Subscriptions of all active members of a channel — the push fan-out set (FR-38). */
    @Query("SELECT ps.* FROM push_subscriptions ps"
            + " JOIN channel_members cm ON cm.user_id = ps.user_id"
            + " WHERE cm.channel_id = :channelId AND cm.status = 'active'")
    List<PushSubscription> findForChannelMembers(String channelId);
}

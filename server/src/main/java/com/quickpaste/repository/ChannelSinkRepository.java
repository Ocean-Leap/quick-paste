package com.quickpaste.repository;

import com.quickpaste.model.ChannelSink;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ChannelSinkRepository extends CrudRepository<ChannelSink, String> {

    Optional<ChannelSink> findByChannelId(String channelId);
}

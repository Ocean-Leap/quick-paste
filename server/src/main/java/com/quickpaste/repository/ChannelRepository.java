package com.quickpaste.repository;

import com.quickpaste.model.Channel;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface ChannelRepository extends CrudRepository<Channel, String> {

    List<Channel> findByCreatedBy(String createdBy);

    @Query("SELECT * FROM channels WHERE id IN (:ids)")
    List<Channel> findByIds(List<String> ids);
}

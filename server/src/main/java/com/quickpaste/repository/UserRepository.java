package com.quickpaste.repository;

import com.quickpaste.model.User;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserRepository extends CrudRepository<User, String> {

    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleSub(String googleSub);

    boolean existsByIsAdminTrue();

    @Query("SELECT * FROM users ORDER BY created_at ASC")
    List<User> listAll();

    @Query("SELECT * FROM users WHERE id IN (:ids)")
    List<User> findByIds(List<String> ids);

    @Query("UPDATE users SET last_login_at = :at WHERE id = :id")
    void touchLastLogin(String id, Instant at);

    @Query("UPDATE users SET google_sub = :googleSub, display_name = :displayName WHERE id = :id")
    void linkGoogle(String id, String googleSub, String displayName);

    @Query("UPDATE users SET status = :status WHERE id = :id")
    void updateStatus(String id, String status);
}

package com.quickpaste.service;

import com.quickpaste.error.ApiException;
import com.quickpaste.model.User;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.repository.ApiTokenRepository;
import com.quickpaste.repository.StoredFileRepository;
import com.quickpaste.repository.UserRepository;
import com.quickpaste.security.SessionService;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.List;

/** Admin user management (FR-49). */
@Singleton
public class AdminService {

    private final UserRepository users;
    private final StoredFileRepository files;
    private final SessionService sessionService;
    private final ApiTokenRepository tokens;
    private final AccountService accountService;

    public AdminService(UserRepository users, StoredFileRepository files, SessionService sessionService,
                        ApiTokenRepository tokens, AccountService accountService) {
        this.users = users;
        this.files = files;
        this.sessionService = sessionService;
        this.tokens = tokens;
        this.accountService = accountService;
    }

    public void requireAdmin(User actor) {
        if (!actor.isAdmin()) {
            throw ApiException.notAuthorized("Admin access required.");
        }
    }

    public List<Dtos.AdminUserDto> listUsers(User actor) {
        requireAdmin(actor);
        return users.listAll().stream()
                .map(u -> Dtos.AdminUserDto.from(u, files.totalSizeByUser(u.id())))
                .toList();
    }

    public Dtos.AdminUserDto setStatus(User actor, String userId, String status) {
        requireAdmin(actor);
        if (!User.STATUS_ACTIVE.equals(status) && !User.STATUS_DISABLED.equals(status)) {
            throw ApiException.validation("status must be 'active' or 'disabled'.");
        }
        User target = users.findById(userId).orElseThrow(ApiException::notFound);
        if (target.id().equals(actor.id())) {
            throw ApiException.validation("You cannot change your own status.");
        }
        users.updateStatus(target.id(), status);
        if (User.STATUS_DISABLED.equals(status)) {
            sessionService.revokeAllForUser(target.id());
            tokens.revokeAllForUser(target.id(), Instant.now());
        }
        return Dtos.AdminUserDto.from(users.findById(userId).orElseThrow(ApiException::notFound),
                files.totalSizeByUser(userId));
    }

    public void deleteUser(User actor, String userId) {
        requireAdmin(actor);
        User target = users.findById(userId).orElseThrow(ApiException::notFound);
        if (target.id().equals(actor.id())) {
            throw ApiException.validation("You cannot delete your own account here; use DELETE /api/account.");
        }
        accountService.deleteUser(target);
    }
}

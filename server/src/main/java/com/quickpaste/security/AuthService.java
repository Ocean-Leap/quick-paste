package com.quickpaste.security;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.Channel;
import com.quickpaste.model.ChannelMember;
import com.quickpaste.model.User;
import com.quickpaste.repository.ChannelMemberRepository;
import com.quickpaste.repository.ChannelRepository;
import com.quickpaste.repository.UserRepository;
import com.quickpaste.util.Ids;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Locale;

/**
 * Login gate and user provisioning (FR-1..FR-3, FR-6, FR-9): allowlist/invite check,
 * admin bootstrap, private-channel creation, and pending-invite activation.
 */
@Singleton
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    private final QuickPasteConfig config;
    private final UserRepository users;
    private final ChannelRepository channels;
    private final ChannelMemberRepository members;
    private final SessionService sessionService;

    public AuthService(QuickPasteConfig config, UserRepository users, ChannelRepository channels,
                       ChannelMemberRepository members, SessionService sessionService) {
        this.config = config;
        this.users = users;
        this.channels = channels;
        this.members = members;
        this.sessionService = sessionService;
    }

    public record LoginOutcome(User user, SessionService.NewSession session) {
    }

    public LoginOutcome completeLogin(OidcService.GoogleIdentity identity, @Nullable String userAgent) {
        String email = identity.email().toLowerCase(Locale.ROOT);
        User user = users.findByGoogleSub(identity.sub()).orElse(null);
        if (user == null) {
            user = users.findByEmail(email).orElse(null);
            if (user != null) {
                users.linkGoogle(user.id(), identity.sub(), identity.displayName());
            }
        }
        if (user == null) {
            boolean allowlisted = config.allowedEmailList().contains(email);
            boolean invited = !members.findByInvitedEmailAndStatus(email, ChannelMember.STATUS_PENDING).isEmpty();
            if (!allowlisted && !invited) {
                LOG.info("Rejected login for unlisted email");
                throw ApiException.notAuthorized("Your Google account is not authorized. Ask an admin for access.");
            }
            boolean admin = allowlisted && !users.existsByIsAdminTrue();
            user = createUser(email, identity.sub(), identity.displayName(), admin);
        }
        if (!User.STATUS_ACTIVE.equals(user.status())) {
            throw ApiException.notAuthorized("This account is disabled.");
        }
        activatePendingInvites(user);
        users.touchLastLogin(user.id(), Instant.now());
        return new LoginOutcome(user, sessionService.create(user, userAgent));
    }

    /** Fixed identity for QP_DEV_AUTH_BYPASS — local development only. */
    public synchronized User devUser() {
        String email = config.getDevUserEmail().toLowerCase(Locale.ROOT);
        return users.findByEmail(email)
                .orElseGet(() -> createUser(email, null, "Dev User", true));
    }

    /**
     * QP_AUTH_MODE=local (FR-1a): single-owner password sign-in for LAN deployments
     * where Google OIDC is impossible (no public FQDN — Google rejects non-localhost
     * http:// and IP-literal redirect URIs). One owner account, provisioned on first
     * successful login.
     */
    public LoginOutcome localLogin(String password, @Nullable String userAgent) {
        if (!config.isLocalMode()) {
            throw ApiException.notFound();
        }
        String configured = config.getLocalPassword();
        if (configured == null || configured.isBlank()) {
            throw ApiException.validation(
                    "QP_AUTH_MODE=local requires QP_LOCAL_PASSWORD to be set.");
        }
        if (password == null || !com.quickpaste.util.CryptoUtil.constantTimeEquals(
                com.quickpaste.util.Hashing.sha256Hex(configured),
                com.quickpaste.util.Hashing.sha256Hex(password))) {
            throw new ApiException(401, "unauthenticated", "Wrong password.", java.util.Map.of());
        }
        User owner = ownerUser();
        if (!User.STATUS_ACTIVE.equals(owner.status())) {
            throw ApiException.notAuthorized("This account is disabled.");
        }
        users.touchLastLogin(owner.id(), Instant.now());
        return new LoginOutcome(owner, sessionService.create(owner, userAgent));
    }

    private synchronized User ownerUser() {
        return users.findByEmail("owner@local")
                .orElseGet(() -> createUser("owner@local", null, "Owner", true));
    }

    private User createUser(String email, @Nullable String googleSub, String displayName, boolean admin) {
        Instant now = Instant.now();
        User user = new User(Ids.newUlid(), email, googleSub, displayName, admin, User.STATUS_ACTIVE, now, null);
        try {
            users.save(user);
        } catch (RuntimeException e) {
            // Concurrent first-login race: the unique email constraint won; reuse that row.
            return users.findByEmail(email).orElseThrow(() -> e);
        }
        Channel privateChannel = new Channel(Ids.newUlid(), "Private", Channel.TYPE_PRIVATE, user.id(), now);
        channels.save(privateChannel);
        members.save(new ChannelMember(Ids.newUlid(), privateChannel.id(), user.id(), null,
                ChannelMember.ROLE_OWNER, ChannelMember.STATUS_ACTIVE, now));
        return user;
    }

    private void activatePendingInvites(User user) {
        Instant now = Instant.now();
        for (ChannelMember pending : members.findByInvitedEmailAndStatus(
                user.email(), ChannelMember.STATUS_PENDING)) {
            if (members.findByChannelIdAndUserId(pending.channelId(), user.id()).isPresent()) {
                members.deleteById(pending.id());
            } else {
                members.activate(pending.id(), user.id(), now);
            }
        }
    }
}

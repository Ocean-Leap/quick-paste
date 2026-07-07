package com.quickpaste;

import com.quickpaste.error.ApiException;
import com.quickpaste.model.ChannelMember;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.repository.ChannelMemberRepository;
import com.quickpaste.security.AuthService;
import com.quickpaste.security.OidcService;
import com.quickpaste.service.ChannelService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-2/FR-3/FR-6/FR-9: allowlist gate, admin bootstrap, private channel, invite activation. */
@MicronautTest(transactional = false)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthServiceTest extends DbTestBase {

    @Inject
    AuthService authService;

    @Inject
    ChannelService channelService;

    @Inject
    ChannelMemberRepository members;

    @Inject
    TestData testData;

    @Test
    @Order(1)
    void allowlistedFirstLoginBootstrapsAdminAndPrivateChannel() {
        AuthService.LoginOutcome outcome = authService.completeLogin(
                new OidcService.GoogleIdentity("admin@test.local", "google-sub-admin", "Admin"), "junit");
        assertTrue(outcome.user().isAdmin(), "first allowlisted login becomes admin (FR-3)");
        assertEquals(1, channelService.listForUser(outcome.user()).size());
        assertEquals("private", channelService.listForUser(outcome.user()).get(0).type());
        assertTrue(outcome.session().cookieValue().contains("."));
    }

    @Test
    @Order(2)
    void unlistedUninvitedEmailIsRejected() {
        ApiException e = assertThrows(ApiException.class, () -> authService.completeLogin(
                new OidcService.GoogleIdentity("stranger@test.local", "google-sub-stranger", "S"), "junit"));
        assertEquals(403, e.status());
    }

    @Test
    @Order(3)
    void pendingInviteActivatesOnFirstLogin() {
        TestData.Fixture admin = testData.user("authsvc-admin@test.local", true);
        Dtos.ChannelDto team = channelService.create(admin.user(), "invite-team");
        Dtos.MemberDto pending = channelService.invite(admin.user(), team.id(), "newcomer@test.local");
        assertEquals("pending", pending.status());

        AuthService.LoginOutcome outcome = authService.completeLogin(
                new OidcService.GoogleIdentity("newcomer@test.local", "google-sub-new", "New"), "junit");
        ChannelMember activated = members.findByChannelIdAndUserId(team.id(), outcome.user().id())
                .orElseThrow();
        assertEquals(ChannelMember.STATUS_ACTIVE, activated.status());
        // Private channel + invited channel.
        assertEquals(2, channelService.listForUser(outcome.user()).size());
    }
}

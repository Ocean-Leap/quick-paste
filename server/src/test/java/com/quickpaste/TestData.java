package com.quickpaste;

import com.quickpaste.model.ApiToken;
import com.quickpaste.model.Channel;
import com.quickpaste.model.ChannelMember;
import com.quickpaste.model.User;
import com.quickpaste.repository.ChannelMemberRepository;
import com.quickpaste.repository.ChannelRepository;
import com.quickpaste.repository.UserRepository;
import com.quickpaste.security.TokenService;
import com.quickpaste.util.Ids;
import jakarta.inject.Singleton;

import java.time.Instant;

/** Test fixtures: users with a private channel and a write-scope bearer token. */
@Singleton
public class TestData {

    private final UserRepository users;
    private final ChannelRepository channels;
    private final ChannelMemberRepository members;
    private final TokenService tokenService;

    public TestData(UserRepository users, ChannelRepository channels, ChannelMemberRepository members,
                    TokenService tokenService) {
        this.users = users;
        this.channels = channels;
        this.members = members;
        this.tokenService = tokenService;
    }

    public record Fixture(User user, String privateChannelId, String bearer) {
    }

    public Fixture user(String email, boolean admin) {
        Instant now = Instant.now();
        User user = new User(Ids.newUlid(), email, null, "Test " + email, admin,
                User.STATUS_ACTIVE, now, null);
        users.save(user);
        Channel channel = new Channel(Ids.newUlid(), "Private", Channel.TYPE_PRIVATE, user.id(), now);
        channels.save(channel);
        members.save(new ChannelMember(Ids.newUlid(), channel.id(), user.id(), null,
                ChannelMember.ROLE_OWNER, ChannelMember.STATUS_ACTIVE, now));
        String bearer = tokenService.mint(user, "test", ApiToken.SCOPE_WRITE).plainToken();
        return new Fixture(user, channel.id(), bearer);
    }

    public String readOnlyToken(User user) {
        return tokenService.mint(user, "ro", ApiToken.SCOPE_READ).plainToken();
    }
}

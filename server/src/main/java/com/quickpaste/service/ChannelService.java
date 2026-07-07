package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.Channel;
import com.quickpaste.model.ChannelMember;
import com.quickpaste.model.User;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.repository.ChannelMemberRepository;
import com.quickpaste.repository.ChannelRepository;
import com.quickpaste.repository.UserRepository;
import com.quickpaste.util.Ids;
import com.quickpaste.ws.WsRegistry;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Channels + membership (SPEC §3, §5.2). Authorization = active membership; non-members
 * get 404 so channel existence is never leaked (§9.5).
 */
@Singleton
public class ChannelService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final QuickPasteConfig config;
    private final ChannelRepository channels;
    private final ChannelMemberRepository members;
    private final UserRepository users;
    private final FileService fileService;
    private final WsRegistry ws;

    public ChannelService(QuickPasteConfig config, ChannelRepository channels, ChannelMemberRepository members,
                          UserRepository users, FileService fileService, WsRegistry ws) {
        this.config = config;
        this.channels = channels;
        this.members = members;
        this.users = users;
        this.fileService = fileService;
        this.ws = ws;
    }

    /** Active membership or 404 — the single authorization gate for clip/file/WS access. */
    public ChannelMember requireActiveMember(String userId, String channelId) {
        return members.findByChannelIdAndUserId(channelId, userId)
                .filter(m -> ChannelMember.STATUS_ACTIVE.equals(m.status()))
                .orElseThrow(ApiException::notFound);
    }

    public List<String> activeChannelIds(String userId) {
        return members.findByUserIdAndStatus(userId, ChannelMember.STATUS_ACTIVE).stream()
                .map(ChannelMember::channelId)
                .toList();
    }

    public List<Dtos.ChannelDto> listForUser(User user) {
        List<ChannelMember> memberships = members.findByUserIdAndStatus(user.id(), ChannelMember.STATUS_ACTIVE);
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<String, String> roleByChannel = memberships.stream()
                .collect(Collectors.toMap(ChannelMember::channelId, ChannelMember::role));
        List<Channel> found = channels.findByIds(memberships.stream().map(ChannelMember::channelId).toList());
        return found.stream()
                .sorted(Comparator
                        .comparing((Channel c) -> !Channel.TYPE_PRIVATE.equals(c.type()))
                        .thenComparing(Channel::name, String.CASE_INSENSITIVE_ORDER))
                .map(c -> Dtos.ChannelDto.from(c, roleByChannel.get(c.id())))
                .toList();
    }

    public Dtos.ChannelDto create(User user, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 64) {
            throw ApiException.validation("Channel name must be 1-64 characters.");
        }
        Instant now = Instant.now();
        Channel channel = new Channel(Ids.newUlid(), name, Channel.TYPE_SHARED, user.id(), now);
        channels.save(channel);
        members.save(new ChannelMember(Ids.newUlid(), channel.id(), user.id(), null,
                ChannelMember.ROLE_OWNER, ChannelMember.STATUS_ACTIVE, now));
        Dtos.ChannelDto dto = Dtos.ChannelDto.from(channel, ChannelMember.ROLE_OWNER);
        ws.channelJoined(user.id(), dto);
        return dto;
    }

    public void delete(User user, String channelId) {
        ChannelMember membership = requireActiveMember(user.id(), channelId);
        Channel channel = channels.findById(channelId).orElseThrow(ApiException::notFound);
        if (!ChannelMember.ROLE_OWNER.equals(membership.role())) {
            throw ApiException.notAuthorized("Only the channel owner can delete it.");
        }
        if (!Channel.TYPE_SHARED.equals(channel.type())) {
            throw ApiException.validation("The private channel cannot be deleted.");
        }
        fileService.deleteChannelDir(channelId);
        channels.deleteById(channelId); // cascades members, clips, file rows
        ws.channelDeleted(channelId);
    }

    public List<Dtos.MemberDto> listMembers(User user, String channelId) {
        requireActiveMember(user.id(), channelId);
        List<ChannelMember> rows = members.findByChannelId(channelId);
        List<String> userIds = rows.stream()
                .map(ChannelMember::userId).filter(java.util.Objects::nonNull).toList();
        Map<String, User> usersById = userIds.isEmpty() ? Map.of()
                : users.findByIds(userIds).stream()
                        .collect(Collectors.toMap(User::id, Function.identity()));
        List<Dtos.MemberDto> result = new ArrayList<>();
        for (ChannelMember row : rows) {
            if (row.userId() != null && usersById.containsKey(row.userId())) {
                User u = usersById.get(row.userId());
                result.add(new Dtos.MemberDto(row.id(), u.id(), u.email(), u.displayName(),
                        row.role(), row.status()));
            } else {
                result.add(new Dtos.MemberDto(row.id(), null,
                        row.invitedEmail() == null ? "" : row.invitedEmail(), "",
                        row.role(), row.status()));
            }
        }
        return result;
    }

    public Dtos.MemberDto invite(User inviter, String channelId, String rawEmail) {
        ChannelMember membership = requireActiveMember(inviter.id(), channelId);
        Channel channel = channels.findById(channelId).orElseThrow(ApiException::notFound);
        if (!ChannelMember.ROLE_OWNER.equals(membership.role())) {
            throw ApiException.notAuthorized("Only the channel owner can invite members.");
        }
        if (!Channel.TYPE_SHARED.equals(channel.type())) {
            throw ApiException.validation("The private channel cannot be shared.");
        }
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) {
            throw ApiException.validation("A valid email address is required.");
        }
        if (members.countByChannelId(channelId) >= config.getChannelMaxMembers()) {
            throw ApiException.validation("Channel member limit reached (QP_CHANNEL_MAX_MEMBERS).");
        }

        User existing = users.findByEmail(email).orElse(null);
        Instant now = Instant.now();
        if (existing != null) {
            if (members.findByChannelIdAndUserId(channelId, existing.id()).isPresent()) {
                throw ApiException.conflict("Already a member of this channel.");
            }
            ChannelMember row = new ChannelMember(Ids.newUlid(), channelId, existing.id(), null,
                    ChannelMember.ROLE_MEMBER, ChannelMember.STATUS_ACTIVE, now);
            members.save(row);
            ws.channelJoined(existing.id(), Dtos.ChannelDto.from(channel, ChannelMember.ROLE_MEMBER));
            return new Dtos.MemberDto(row.id(), existing.id(), existing.email(), existing.displayName(),
                    row.role(), row.status());
        }

        // First-time access is admin-gated (FR-2): only admins may invite emails
        // that have never signed in.
        if (!inviter.isAdmin()) {
            throw ApiException.notAuthorized("Only an admin can invite someone who has not signed in before.");
        }
        if (members.findByChannelIdAndInvitedEmail(channelId, email).isPresent()) {
            throw ApiException.conflict("This email is already invited.");
        }
        ChannelMember row = new ChannelMember(Ids.newUlid(), channelId, null, email,
                ChannelMember.ROLE_MEMBER, ChannelMember.STATUS_PENDING, null);
        members.save(row);
        return new Dtos.MemberDto(row.id(), null, email, "", row.role(), row.status());
    }

    public void removeMember(User actor, String channelId, String memberId) {
        ChannelMember actorMembership = requireActiveMember(actor.id(), channelId);
        ChannelMember target = members.findById(memberId)
                .filter(m -> m.channelId().equals(channelId))
                .orElseThrow(ApiException::notFound);
        Channel channel = channels.findById(channelId).orElseThrow(ApiException::notFound);
        if (!Channel.TYPE_SHARED.equals(channel.type())) {
            throw ApiException.validation("The private channel membership cannot be changed.");
        }
        boolean actorIsOwner = ChannelMember.ROLE_OWNER.equals(actorMembership.role());
        boolean removingSelf = actor.id().equals(target.userId());
        if (ChannelMember.ROLE_OWNER.equals(target.role())) {
            throw ApiException.validation("The owner cannot leave or be removed; delete the channel instead.");
        }
        if (!actorIsOwner && !removingSelf) {
            throw ApiException.notFound();
        }
        members.deleteById(target.id());
        if (target.userId() != null && ChannelMember.STATUS_ACTIVE.equals(target.status())) {
            ws.channelLeft(channelId, target.userId());
        }
    }
}

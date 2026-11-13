package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.Channel;
import com.quickpaste.model.ChannelMember;
import com.quickpaste.model.ChannelSink;
import com.quickpaste.model.User;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.model.dto.Requests;
import com.quickpaste.repository.ChannelMemberRepository;
import com.quickpaste.repository.ChannelRepository;
import com.quickpaste.repository.ChannelSinkRepository;
import com.quickpaste.repository.UserRepository;
import com.quickpaste.security.SessionService;
import com.quickpaste.util.CryptoUtil;
import com.quickpaste.util.HostSafety;
import com.quickpaste.util.Ids;
import com.quickpaste.util.TagsJson;
import com.quickpaste.ws.WsRegistry;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Channels + membership (SPEC §3, §5.2, §5.14). Authorization = active membership;
 * non-members get 404 so channel existence is never leaked (§9.5).
 */
@Singleton
public class ChannelService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int TOKEN_PREFIX_LEN = 12;

    private final QuickPasteConfig config;
    private final ChannelRepository channels;
    private final ChannelMemberRepository members;
    private final ChannelSinkRepository sinks;
    private final UserRepository users;
    private final FileService fileService;
    private final WsRegistry ws;
    private final byte[] aesKey;

    public ChannelService(QuickPasteConfig config, ChannelRepository channels, ChannelMemberRepository members,
                          ChannelSinkRepository sinks, UserRepository users, FileService fileService,
                          WsRegistry ws, SessionService sessions) {
        this.config = config;
        this.channels = channels;
        this.members = members;
        this.sinks = sinks;
        this.users = users;
        this.fileService = fileService;
        this.ws = ws;
        this.aesKey = sessions.aesKey();
    }

    /** Active membership or 404 — the single authorization gate for clip/file/WS access. */
    public ChannelMember requireActiveMember(String userId, String channelId) {
        return members.findByChannelIdAndUserId(channelId, userId)
                .filter(m -> ChannelMember.STATUS_ACTIVE.equals(m.status()))
                .orElseThrow(ApiException::notFound);
    }

    public Channel requireChannel(String channelId) {
        return channels.findById(channelId).orElseThrow(ApiException::notFound);
    }

    public boolean isExternal(String channelId) {
        return channels.findById(channelId)
                .map(c -> Channel.TYPE_EXTERNAL.equals(c.type()))
                .orElse(false);
    }

    /** Rejects file uploads/clips on external sink channels (FR-63). */
    public void rejectFilesOnExternal(String channelId) {
        if (isExternal(channelId)) {
            throw ApiException.validation(
                    "External sink channels accept text only (Steward Mind note/quote/link).");
        }
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
                        .comparingInt((Channel c) -> typeRank(c.type()))
                        .thenComparing(Channel::name, String.CASE_INSENSITIVE_ORDER))
                .map(c -> toDto(c, roleByChannel.get(c.id())))
                .toList();
    }

    public Dtos.ChannelDto create(User user, Requests.CreateChannelRequest request) {
        String type = request.type() == null || request.type().isBlank()
                ? Channel.TYPE_SHARED
                : request.type().trim().toLowerCase(Locale.ROOT);
        if (Channel.TYPE_EXTERNAL.equals(type)) {
            return createExternal(user, request);
        }
        if (!Channel.TYPE_SHARED.equals(type)) {
            throw ApiException.validation("type must be 'shared' or 'external'.");
        }
        String name = normalizeName(request.name());
        Instant now = Instant.now();
        Channel channel = new Channel(Ids.newUlid(), name, Channel.TYPE_SHARED, user.id(), now);
        channels.save(channel);
        members.save(new ChannelMember(Ids.newUlid(), channel.id(), user.id(), null,
                ChannelMember.ROLE_OWNER, ChannelMember.STATUS_ACTIVE, now));
        Dtos.ChannelDto dto = Dtos.ChannelDto.from(channel, ChannelMember.ROLE_OWNER);
        ws.channelJoined(user.id(), dto);
        return dto;
    }

    public Dtos.ChannelDto createExternal(User user, Requests.CreateChannelRequest request) {
        String name = normalizeName(request.name());
        String provider = request.provider() == null ? "" : request.provider().trim().toLowerCase(Locale.ROOT);
        if (!ChannelSink.PROVIDER_STEWARD.equals(provider)) {
            throw ApiException.validation("provider must be 'steward'.");
        }
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String token = normalizeToken(request.token());
        List<String> tags = normalizeTags(request.defaultTags());
        Instant now = Instant.now();
        Channel channel = new Channel(Ids.newUlid(), name, Channel.TYPE_EXTERNAL, user.id(), now);
        channels.save(channel);
        members.save(new ChannelMember(Ids.newUlid(), channel.id(), user.id(), null,
                ChannelMember.ROLE_OWNER, ChannelMember.STATUS_ACTIVE, now));
        ChannelSink sink = new ChannelSink(
                channel.id(),
                ChannelSink.PROVIDER_STEWARD,
                baseUrl,
                CryptoUtil.encryptAesGcm(aesKey, token),
                tokenPrefix(token),
                TagsJson.write(tags),
                now,
                now);
        sinks.save(sink);
        Dtos.ChannelDto dto = toDto(channel, ChannelMember.ROLE_OWNER);
        ws.channelJoined(user.id(), dto);
        return dto;
    }

    public Dtos.ChannelDto patchSink(User user, String channelId, Requests.PatchSinkRequest request) {
        ChannelMember membership = requireActiveMember(user.id(), channelId);
        if (!ChannelMember.ROLE_OWNER.equals(membership.role())) {
            throw ApiException.notAuthorized("Only the channel owner can update the sink.");
        }
        Channel channel = requireChannel(channelId);
        if (!Channel.TYPE_EXTERNAL.equals(channel.type())) {
            throw ApiException.validation("Only external channels have a sink configuration.");
        }
        ChannelSink existing = sinks.findByChannelId(channelId).orElseThrow(ApiException::notFound);
        boolean any = false;
        String baseUrl = existing.baseUrl();
        String ciphertext = existing.tokenCiphertext();
        String prefix = existing.tokenPrefix();
        String tagsJson = existing.defaultTags();
        if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
            baseUrl = normalizeBaseUrl(request.baseUrl());
            any = true;
        }
        if (request.token() != null && !request.token().isBlank()) {
            String token = normalizeToken(request.token());
            ciphertext = CryptoUtil.encryptAesGcm(aesKey, token);
            prefix = tokenPrefix(token);
            any = true;
        }
        if (request.defaultTags() != null) {
            tagsJson = TagsJson.write(normalizeTags(request.defaultTags()));
            any = true;
        }
        if (!any) {
            throw ApiException.validation("Provide token, baseUrl, and/or defaultTags to update.");
        }
        Instant now = Instant.now();
        ChannelSink updated = new ChannelSink(existing.channelId(), existing.provider(), baseUrl,
                ciphertext, prefix, tagsJson, existing.createdAt(), now);
        sinks.update(updated);
        return toDto(channel, membership.role());
    }

    public void delete(User user, String channelId) {
        ChannelMember membership = requireActiveMember(user.id(), channelId);
        Channel channel = channels.findById(channelId).orElseThrow(ApiException::notFound);
        if (!ChannelMember.ROLE_OWNER.equals(membership.role())) {
            throw ApiException.notAuthorized("Only the channel owner can delete it.");
        }
        if (Channel.TYPE_PRIVATE.equals(channel.type())) {
            throw ApiException.validation("The private channel cannot be deleted.");
        }
        if (!Channel.TYPE_SHARED.equals(channel.type()) && !Channel.TYPE_EXTERNAL.equals(channel.type())) {
            throw ApiException.validation("This channel cannot be deleted.");
        }
        fileService.deleteChannelDir(channelId);
        channels.deleteById(channelId); // cascades members, clips, files, sinks, deliveries
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
            throw ApiException.validation("Only shared channels can be invited to.");
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
            throw ApiException.validation("This channel's membership cannot be changed.");
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

    private Dtos.ChannelDto toDto(Channel channel, String role) {
        if (!Channel.TYPE_EXTERNAL.equals(channel.type())) {
            return Dtos.ChannelDto.from(channel, role);
        }
        Optional<ChannelSink> sink = sinks.findByChannelId(channel.id());
        Dtos.SinkDto sinkDto = sink.map(s -> new Dtos.SinkDto(
                s.provider(),
                s.baseUrl(),
                s.tokenPrefix(),
                TagsJson.read(s.defaultTags()).isEmpty() ? null : TagsJson.read(s.defaultTags())
        )).orElse(null);
        return Dtos.ChannelDto.from(channel, role, sinkDto);
    }

    private static int typeRank(String type) {
        if (Channel.TYPE_PRIVATE.equals(type)) {
            return 0;
        }
        if (Channel.TYPE_EXTERNAL.equals(type)) {
            return 1;
        }
        return 2;
    }

    private static String normalizeName(@Nullable String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 64) {
            throw ApiException.validation("Channel name must be 1-64 characters.");
        }
        return name;
    }

    static String normalizeBaseUrl(@Nullable String raw) {
        return HostSafety.requirePublicHttpsBase(raw);
    }

    static String normalizeToken(@Nullable String raw) {
        String token = raw == null ? "" : raw.trim();
        if (token.isEmpty() || token.length() > 512) {
            throw ApiException.validation("token is required (≤ 512 characters).");
        }
        if (!token.startsWith("stw_")) {
            throw ApiException.validation("Steward tokens must start with stw_.");
        }
        return token;
    }

    static List<String> normalizeTags(@Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > 32) {
            throw ApiException.validation("At most 32 default tags are allowed.");
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String tag : raw) {
            if (tag == null) {
                continue;
            }
            String t = tag.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() > 64) {
                throw ApiException.validation("Each tag must be ≤ 64 characters.");
            }
            out.add(t);
        }
        return out;
    }

    static String tokenPrefix(String token) {
        return token.length() <= TOKEN_PREFIX_LEN ? token : token.substring(0, TOKEN_PREFIX_LEN);
    }
}

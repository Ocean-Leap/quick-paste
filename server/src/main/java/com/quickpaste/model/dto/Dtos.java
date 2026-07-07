package com.quickpaste.model.dto;

import com.quickpaste.model.ApiToken;
import com.quickpaste.model.Channel;
import com.quickpaste.model.Clip;
import com.quickpaste.model.ExportJob;
import com.quickpaste.model.StoredFile;
import com.quickpaste.model.User;
import com.quickpaste.util.Iso;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** Response DTOs (SPEC §8.2/§8.5). Timestamps are ISO-8601 strings. */
public final class Dtos {

    private Dtos() {
    }

    @Serdeable
    public record UserDto(String id, String email, String displayName, boolean isAdmin) {
        public static UserDto from(User u) {
            return new UserDto(u.id(), u.email(), u.displayName(), u.isAdmin());
        }
    }

    @Serdeable
    public record ChannelDto(String id, String name, String type, String createdBy, String role) {
        public static ChannelDto from(Channel c, String role) {
            return new ChannelDto(c.id(), c.name(), c.type(), c.createdBy(), role);
        }
    }

    @Serdeable
    public record MemberDto(
            String id,
            @Nullable String userId,
            String email,
            String displayName,
            String role,
            String status) {
    }

    @Serdeable
    public record FileRefDto(String id, String name, long size, String mime) {
        public static FileRefDto from(StoredFile f) {
            return new FileRefDto(f.id(), f.name(), f.size(), f.mime());
        }
    }

    @Serdeable
    public record ClipDto(
            String id,
            String channelId,
            String type,
            @Nullable String text,
            @Nullable FileRefDto file,
            String origin,
            String originUserId,
            String createdAt,
            String expiresAt) {
        public static ClipDto from(Clip c, @Nullable StoredFile file) {
            return new ClipDto(
                    c.id(),
                    c.channelId(),
                    c.type(),
                    c.text(),
                    file == null ? null : FileRefDto.from(file),
                    c.originDevice(),
                    c.originUserId(),
                    Iso.format(c.createdAt()),
                    Iso.format(c.expiresAt()));
        }
    }

    @Serdeable
    public record ClipListResponse(List<ClipDto> items, @Nullable String nextCursor) {
    }

    @Serdeable
    public record FileMetaDto(
            String id, String name, long size, String mime, String checksum,
            String createdAt, String expiresAt) {
        public static FileMetaDto from(StoredFile f) {
            return new FileMetaDto(f.id(), f.name(), f.size(), f.mime(), f.checksum(),
                    Iso.format(f.createdAt()), Iso.format(f.expiresAt()));
        }
    }

    @Serdeable
    public record FileUploadedDto(String fileId, String name, long size, String mime) {
    }

    @Serdeable
    public record TokenDto(
            String id, String name, String prefix, String scope, String createdAt,
            @Nullable String lastUsedAt, @Nullable String expiresAt, @Nullable String revokedAt) {
        public static TokenDto from(ApiToken t) {
            return new TokenDto(t.id(), t.name(), t.prefix(), t.scope(), Iso.format(t.createdAt()),
                    Iso.format(t.lastUsedAt()), Iso.format(t.expiresAt()), Iso.format(t.revokedAt()));
        }
    }

    @Serdeable
    public record TokenCreatedDto(String id, String token, String prefix) {
    }

    @Serdeable
    public record MeResponse(UserDto user, List<ChannelDto> channels) {
    }

    /** Unauthenticated sign-in capability (GET /api/auth/mode). */
    @Serdeable
    public record AuthModeDto(String mode) {
    }

    @Serdeable
    public record ExportJobDto(String jobId, String status, @Nullable String expiresAt) {
        public static ExportJobDto from(ExportJob j) {
            return new ExportJobDto(j.id(), j.status(), Iso.format(j.expiresAt()));
        }
    }

    /** Client-facing config (GET /api/config): size limits + push capability. */
    @Serdeable
    public record ClientConfigDto(
            int maxTextKb,
            int simpleUploadMb,
            int maxFileMb,
            int userQuotaMb,
            boolean thumbnailsEnabled,
            boolean pushEnabled,
            @Nullable String vapidPublicKey) {
    }

    @Serdeable
    public record PushSubscribedDto(String id) {
    }

    @Serdeable
    public record UploadCreatedDto(String uploadId) {
    }

    @Serdeable
    public record AdminUserDto(
            String id, String email, String displayName, boolean isAdmin, String status,
            String createdAt, @Nullable String lastLoginAt, long storageBytes) {
        public static AdminUserDto from(User u, long storageBytes) {
            return new AdminUserDto(u.id(), u.email(), u.displayName(), u.isAdmin(), u.status(),
                    Iso.format(u.createdAt()), Iso.format(u.lastLoginAt()), storageBytes);
        }
    }
}

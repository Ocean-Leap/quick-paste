package com.quickpaste.ws;

import com.quickpaste.model.dto.Dtos;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

/** WebSocket wire shapes (SPEC §8.5). Every server event carries its channelId where relevant. */
public final class WsMessages {

    private WsMessages() {
    }

    /** Client → server. {@code type} is "hello" or "pong"; cursors map channelId → last-seen ULID (or null). */
    @Serdeable
    public record ClientMessage(
            @Nullable String type,
            @Nullable String device,
            @Nullable Map<String, Object> cursors) {
    }

    @Serdeable
    public record ClipCreated(String event, String channelId, Dtos.ClipDto clip) {
        public static ClipCreated of(Dtos.ClipDto clip) {
            return new ClipCreated("clip.created", clip.channelId(), clip);
        }
    }

    @Serdeable
    public record ClipDeleted(String event, String channelId, String id) {
        public static ClipDeleted of(String channelId, String id) {
            return new ClipDeleted("clip.deleted", channelId, id);
        }
    }

    @Serdeable
    public record ClipsCleared(String event, String channelId) {
        public static ClipsCleared of(String channelId) {
            return new ClipsCleared("clips.cleared", channelId);
        }
    }

    @Serdeable
    public record ChannelJoined(String event, Dtos.ChannelDto channel) {
        public static ChannelJoined of(Dtos.ChannelDto channel) {
            return new ChannelJoined("channel.joined", channel);
        }
    }

    @Serdeable
    public record ChannelLeft(String event, String channelId) {
        public static ChannelLeft of(String channelId) {
            return new ChannelLeft("channel.left", channelId);
        }
    }

    @Serdeable
    public record ChannelDeleted(String event, String channelId) {
        public static ChannelDeleted of(String channelId) {
            return new ChannelDeleted("channel.deleted", channelId);
        }
    }

    /**
     * End-of-replay marker per channel: cursor is the ULID of the last replayed clip
     * (or the client's own cursor when nothing was missed; null for an empty channel).
     * Clients must not trust live-event cursor advancement until this arrives (NFR-3).
     */
    @Serdeable
    public record CatchupDone(String event, String channelId, @Nullable String cursor) {
        public static CatchupDone of(String channelId, @Nullable String cursor) {
            return new CatchupDone("catchup.done", channelId, cursor);
        }
    }

    @Serdeable
    public record Ping(String event) {
        public static final Ping INSTANCE = new Ping("ping");
    }
}

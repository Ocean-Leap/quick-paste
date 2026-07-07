package com.quickpaste.ws;

import com.quickpaste.model.dto.Dtos;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process per-channel fan-out registry (SPEC §8.5, NFR-7): channelId → sessions,
 * userId → sessions. Membership changes update subscriptions live. Single instance
 * by design — no broker (NFR-11).
 */
@Singleton
public class WsRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(WsRegistry.class);

    private final Map<String, Set<WebSocketSession>> byChannel = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> byUser = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public WsRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void register(WebSocketSession session, String userId, Collection<String> channelIds) {
        byUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        for (String channelId : channelIds) {
            byChannel.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(session);
        }
    }

    public void unregister(WebSocketSession session) {
        byChannel.values().forEach(set -> set.remove(session));
        byUser.values().forEach(set -> set.remove(session));
    }

    public int countForUser(String userId) {
        Set<WebSocketSession> sessions = byUser.get(userId);
        return sessions == null ? 0 : (int) sessions.stream().filter(WebSocketSession::isOpen).count();
    }

    public int totalOpenSessions() {
        return (int) byUser.values().stream().flatMap(Set::stream).filter(WebSocketSession::isOpen).count();
    }

    public void clipCreated(Dtos.ClipDto clip) {
        broadcast(clip.channelId(), WsMessages.ClipCreated.of(clip));
    }

    public void clipDeleted(String channelId, String clipId) {
        broadcast(channelId, WsMessages.ClipDeleted.of(channelId, clipId));
    }

    public void clipsCleared(String channelId) {
        broadcast(channelId, WsMessages.ClipsCleared.of(channelId));
    }

    /** A user gained a channel: subscribe their open sockets and tell them (SPEC §8.5). */
    public void channelJoined(String userId, Dtos.ChannelDto channel) {
        Set<WebSocketSession> sessions = byUser.get(userId);
        if (sessions == null) {
            return;
        }
        Set<WebSocketSession> channelSet =
                byChannel.computeIfAbsent(channel.id(), k -> ConcurrentHashMap.newKeySet());
        for (WebSocketSession session : sessions) {
            channelSet.add(session);
            send(session, WsMessages.ChannelJoined.of(channel));
        }
    }

    /** A user lost a channel: unsubscribe their sockets and tell them. */
    public void channelLeft(String channelId, String userId) {
        Set<WebSocketSession> sessions = byUser.get(userId);
        Set<WebSocketSession> channelSet = byChannel.get(channelId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (channelSet != null) {
                channelSet.remove(session);
            }
            send(session, WsMessages.ChannelLeft.of(channelId));
        }
    }

    public void channelDeleted(String channelId) {
        Set<WebSocketSession> channelSet = byChannel.remove(channelId);
        if (channelSet != null) {
            for (WebSocketSession session : channelSet) {
                send(session, WsMessages.ChannelDeleted.of(channelId));
            }
        }
    }

    public void closeUserSessions(String userId) {
        Set<WebSocketSession> sessions = byUser.remove(userId);
        if (sessions != null) {
            for (WebSocketSession session : sessions) {
                byChannel.values().forEach(set -> set.remove(session));
                if (session.isOpen()) {
                    session.close(CloseReason.NORMAL);
                }
            }
        }
    }

    /** Catch-up replay to a single session (SPEC §8.5). */
    public void sendCatchup(WebSocketSession session, Dtos.ClipDto clip) {
        send(session, WsMessages.ClipCreated.of(clip));
    }

    /** End-of-replay marker for one channel (SPEC §8.5). */
    public void sendCatchupDone(WebSocketSession session, String channelId, String cursor) {
        send(session, WsMessages.CatchupDone.of(channelId, cursor));
    }

    /** App-level heartbeat (FR-29); stale/closed sessions are pruned. */
    @Scheduled(fixedDelay = "30s", initialDelay = "30s")
    public void heartbeat() {
        for (Set<WebSocketSession> sessions : byUser.values()) {
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    send(session, WsMessages.Ping.INSTANCE);
                } else {
                    unregister(session);
                }
            }
        }
    }

    private void broadcast(String channelId, Object event) {
        Set<WebSocketSession> sessions = byChannel.get(channelId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String json = toJson(event);
        for (WebSocketSession session : sessions) {
            sendRaw(session, json);
        }
    }

    private void send(WebSocketSession session, Object event) {
        sendRaw(session, toJson(event));
    }

    private void sendRaw(WebSocketSession session, String json) {
        try {
            if (session.isOpen()) {
                session.sendAsync(json);
            }
        } catch (RuntimeException e) {
            LOG.debug("WS send failed for session {}: {}", session.getId(), e.getMessage());
        }
    }

    private String toJson(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize WS event", e);
        }
    }
}

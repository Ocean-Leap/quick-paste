package com.quickpaste.ws;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.model.Clip;
import com.quickpaste.repository.ClipRepository;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.service.ChannelService;
import com.quickpaste.service.ClipService;
import io.micronaut.http.HttpRequest;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnError;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * /api/stream (SPEC §8.5). The handshake is authenticated by {@link com.quickpaste.security.AuthFilter};
 * the socket serves all of the user's channels. Catch-up uses the ULID cursor (D-7):
 * sessions register for live events first, then replay — clients dedup by clip id,
 * so nothing is lost across the registration/replay boundary (NFR-3).
 */
@ServerWebSocket("/api/stream")
public class StreamWebSocket {

    private static final Logger LOG = LoggerFactory.getLogger(StreamWebSocket.class);
    private static final String ATTR_USER = "qp.userId";

    private final QuickPasteConfig config;
    private final WsRegistry registry;
    private final ChannelService channelService;
    private final ClipRepository clips;
    private final ClipService clipService;
    private final ObjectMapper mapper;

    public StreamWebSocket(QuickPasteConfig config, WsRegistry registry, ChannelService channelService,
                           ClipRepository clips, ClipService clipService, ObjectMapper mapper) {
        this.config = config;
        this.registry = registry;
        this.channelService = channelService;
        this.clips = clips;
        this.clipService = clipService;
        this.mapper = mapper;
    }

    @OnOpen
    public void onOpen(WebSocketSession session, HttpRequest<?> request) {
        QpPrincipal principal = request.getAttributes()
                .get(QpPrincipal.ATTR, QpPrincipal.class)
                .orElse(null);
        if (principal == null) {
            session.close(CloseReason.POLICY_VIOLATION);
            return;
        }
        String userId = principal.user().id();
        if (registry.countForUser(userId) >= config.getMaxWsConnPerUser()) {
            session.close(new CloseReason(4001, "Too many connections (QP_MAX_WS_CONN_PER_USER)."));
            return;
        }
        session.put(ATTR_USER, userId);
        registry.register(session, userId, channelService.activeChannelIds(userId));
    }

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        String userId = session.get(ATTR_USER, String.class).orElse(null);
        if (userId == null) {
            return;
        }
        WsMessages.ClientMessage parsed;
        try {
            parsed = mapper.readValue(message, WsMessages.ClientMessage.class);
        } catch (Exception e) {
            LOG.debug("Ignoring malformed WS message: {}", e.getMessage());
            return;
        }
        if (parsed == null || parsed.type() == null || "pong".equals(parsed.type())) {
            return;
        }
        if ("hello".equals(parsed.type())) {
            replayCatchup(session, userId, parsed.cursors() == null ? Map.of() : parsed.cursors());
        }
    }

    /** Must match the LIMIT in ClipRepository's catch-up queries. */
    private static final int CATCHUP_BATCH = 500;

    /**
     * Per-channel replay: with a cursor, everything after that ULID; without one,
     * the full live window (a fresh device's initial sync). Replay loops in batches
     * until drained — no silent truncation — and ends with a catchup.done marker so
     * clients know when the cursor is trustworthy again (NFR-3).
     */
    private void replayCatchup(WebSocketSession session, String userId, Map<String, Object> cursors) {
        Instant now = Instant.now();
        for (String channelId : channelService.activeChannelIds(userId)) {
            String cursor = cursors.get(channelId) instanceof String afterId && !afterId.isBlank()
                    ? afterId
                    : null;
            while (true) {
                List<Clip> batch = cursor == null
                        ? clips.findCatchupAll(channelId, now)
                        : clips.findCatchupAfter(channelId, cursor, now);
                for (Clip clip : batch) {
                    registry.sendCatchup(session, clipService.toDto(clip));
                }
                if (!batch.isEmpty()) {
                    cursor = batch.get(batch.size() - 1).id();
                }
                if (batch.size() < CATCHUP_BATCH) {
                    break;
                }
            }
            registry.sendCatchupDone(session, channelId, cursor);
        }
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        registry.unregister(session);
    }

    @OnError
    public void onError(WebSocketSession session, Throwable error) {
        LOG.debug("WS error on session {}: {}", session.getId(), error.getMessage());
        registry.unregister(session);
    }
}

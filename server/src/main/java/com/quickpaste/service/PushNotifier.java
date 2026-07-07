package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.model.Channel;
import com.quickpaste.model.PushSubscription;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.repository.ChannelRepository;
import com.quickpaste.repository.PushSubscriptionRepository;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Web-push fan-out (FR-38): on clip.created, notify every active member's subscriptions,
 * skipping the device that pushed the clip. Payload is metadata only — never clip
 * content (NFR-6). Disabled unless QP_VAPID_* keys are configured. Dead subscriptions
 * (404/410 from the push service) are pruned.
 */
@Singleton
public class PushNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(PushNotifier.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final QuickPasteConfig config;
    private final PushSubscriptionRepository subscriptions;
    private final ChannelRepository channels;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private volatile PushService pushService;

    public PushNotifier(QuickPasteConfig config, PushSubscriptionRepository subscriptions,
                        ChannelRepository channels, ObjectMapper mapper,
                        @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.config = config;
        this.subscriptions = subscriptions;
        this.channels = channels;
        this.mapper = mapper;
        this.executor = executor;
    }

    public void clipCreated(Dtos.ClipDto clip) {
        if (!config.pushEnabled()) {
            return;
        }
        executor.execute(() -> deliver(clip));
    }

    private void deliver(Dtos.ClipDto clip) {
        String channelName = channels.findById(clip.channelId()).map(Channel::name).orElse("a channel");
        List<PushSubscription> targets = subscriptions.findForChannelMembers(clip.channelId());
        if (targets.isEmpty()) {
            return;
        }
        byte[] payload;
        try {
            payload = mapper.writeValueAsString(Map.of(
                    "title", "quick-paste",
                    "body", clip.origin() + " sent "
                            + ("file".equals(clip.type()) ? "a file" : "a clip")
                            + " in " + channelName,
                    "channelId", clip.channelId(),
                    "clipId", clip.id(),
                    "type", clip.type())).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Push payload serialization failed: {}", e.getMessage());
            return;
        }
        for (PushSubscription target : targets) {
            // Skip only the pushing user's own device — labels are not unique across
            // users, so another member's same-labeled device must still be notified.
            if (target.userId().equals(clip.originUserId()) && target.device().equals(clip.origin())) {
                continue;
            }
            sendOne(target, payload);
        }
    }

    private void sendOne(PushSubscription target, byte[] payload) {
        try {
            Notification notification = new Notification(
                    target.endpoint(), target.p256dh(), target.auth(), payload);
            HttpResponse response = service().send(notification);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                // Subscription expired or unsubscribed at the push service — prune it.
                subscriptions.deleteById(target.id());
                LOG.info("Pruned dead push subscription {}", target.id());
            } else if (status >= 400) {
                LOG.warn("Push delivery to subscription {} failed with status {}", target.id(), status);
            }
        } catch (Exception e) {
            LOG.warn("Push delivery to subscription {} failed: {}", target.id(), e.getMessage());
        }
    }

    private PushService service() throws Exception {
        PushService s = pushService;
        if (s == null) {
            synchronized (this) {
                if (pushService == null) {
                    String subject = config.getVapidSubject().isBlank()
                            ? "mailto:admin@localhost"
                            : config.getVapidSubject();
                    pushService = new PushService(
                            config.getVapidPublicKey(), config.getVapidPrivateKey(), subject);
                }
                s = pushService;
            }
        }
        return s;
    }
}

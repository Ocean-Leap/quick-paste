package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NFR-5 / SPEC §9.5 — the tenant-isolation suite: non-members must get 404 on every
 * channel-scoped operation, and membership changes must gate access immediately.
 */
@MicronautTest(transactional = false)
class IsolationTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    @Test
    void nonMemberGets404OnEveryChannelOperation() {
        TestData.Fixture alice = testData.user("iso-alice@test.local", false);
        TestData.Fixture mallory = testData.user("iso-mallory@test.local", false);
        String cid = alice.privateChannelId();

        List<MutableHttpRequest<?>> probes = List.of(
                HttpRequest.GET("/api/channels/" + cid + "/clips"),
                HttpRequest.GET("/api/channels/" + cid + "/clips/latest"),
                HttpRequest.POST("/api/channels/" + cid + "/clips",
                        Map.of("type", "text", "text", "stolen", "origin", "junit")),
                HttpRequest.DELETE("/api/channels/" + cid + "/clips"),
                HttpRequest.GET("/api/channels/" + cid + "/members"),
                HttpRequest.POST("/api/channels/" + cid + "/members", Map.of("email", "x@test.local")),
                HttpRequest.DELETE("/api/channels/" + cid));

        for (MutableHttpRequest<?> probe : probes) {
            HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                    () -> client.toBlocking().exchange(
                            probe.header("Authorization", "Bearer " + mallory.bearer())),
                    "expected 404 for " + probe.getMethodName() + " " + probe.getPath());
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus(),
                    probe.getMethodName() + " " + probe.getPath());
        }
    }

    @Test
    void sharedChannelMembershipGrantsAndRevokesAccess() {
        TestData.Fixture owner = testData.user("iso-owner@test.local", false);
        TestData.Fixture member = testData.user("iso-member@test.local", false);

        // Owner creates a shared channel and invites the (already-registered) member.
        Dtos.ChannelDto team = client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels", Map.of("name", "team"))
                        .header("Authorization", "Bearer " + owner.bearer()),
                Dtos.ChannelDto.class);
        client.toBlocking().exchange(
                HttpRequest.POST("/api/channels/" + team.id() + "/members",
                                Map.of("email", "iso-member@test.local"))
                        .header("Authorization", "Bearer " + owner.bearer()));

        // Member now sees the channel and can write into it.
        Dtos.MeResponse me = client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").header("Authorization", "Bearer " + member.bearer()),
                Dtos.MeResponse.class);
        assertEquals(2, me.channels().size());
        Dtos.ClipDto clip = client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels/" + team.id() + "/clips",
                                Map.of("type", "text", "text", "hello team", "origin", "member-device"))
                        .header("Authorization", "Bearer " + member.bearer()),
                Dtos.ClipDto.class);
        Dtos.ClipDto latest = client.toBlocking().retrieve(
                HttpRequest.GET("/api/channels/" + team.id() + "/clips/latest")
                        .header("Authorization", "Bearer " + owner.bearer()),
                Dtos.ClipDto.class);
        assertEquals(clip.id(), latest.id());

        // A non-owner cannot invite.
        HttpClientResponseException notOwner = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/channels/" + team.id() + "/members",
                                        Map.of("email", "iso-alice@test.local"))
                                .header("Authorization", "Bearer " + member.bearer())));
        assertEquals(HttpStatus.FORBIDDEN, notOwner.getStatus());

        // A non-admin owner cannot invite an email that has never signed in (FR-2).
        HttpClientResponseException adminGate = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/channels/" + team.id() + "/members",
                                        Map.of("email", "ghost-never-signed-in@test.local"))
                                .header("Authorization", "Bearer " + owner.bearer())));
        assertEquals(HttpStatus.FORBIDDEN, adminGate.getStatus());

        // Member leaves — and instantly loses access.
        Map<String, List<Dtos.MemberDto>> membersList = client.toBlocking().retrieve(
                HttpRequest.GET("/api/channels/" + team.id() + "/members")
                        .header("Authorization", "Bearer " + member.bearer()),
                Argument.mapOf(Argument.of(String.class), Argument.listOf(Dtos.MemberDto.class)));
        String memberRowId = membersList.get("items").stream()
                .filter(m -> member.user().id().equals(m.userId()))
                .findFirst().orElseThrow().id();
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/channels/" + team.id() + "/members/" + memberRowId)
                        .header("Authorization", "Bearer " + member.bearer()));
        HttpClientResponseException afterLeave = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.GET("/api/channels/" + team.id() + "/clips/latest")
                                .header("Authorization", "Bearer " + member.bearer())));
        assertEquals(HttpStatus.NOT_FOUND, afterLeave.getStatus());
        assertTrue(client.toBlocking().retrieve(
                        HttpRequest.GET("/api/auth/me").header("Authorization", "Bearer " + member.bearer()),
                        Dtos.MeResponse.class)
                .channels().stream().noneMatch(c -> c.id().equals(team.id())));
    }
}

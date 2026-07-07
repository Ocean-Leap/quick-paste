package com.quickpaste.controller;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.model.dto.Requests;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.service.ChannelService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.List;
import java.util.Map;

/** Channels + membership endpoints (SPEC §8.2). */
@Controller("/api/channels")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @Get
    public Map<String, List<Dtos.ChannelDto>> list(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal) {
        return Map.of("items", channelService.listForUser(principal.user()));
    }

    @Post
    public HttpResponse<Dtos.ChannelDto> create(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            @Body Requests.CreateChannelRequest body) {
        return HttpResponse.created(channelService.create(principal.user(), body.name()));
    }

    @Delete("/{id}")
    public HttpResponse<Void> delete(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String id) {
        channelService.delete(principal.user(), id);
        return HttpResponse.noContent();
    }

    @Get("/{id}/members")
    public Map<String, List<Dtos.MemberDto>> members(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String id) {
        return Map.of("items", channelService.listMembers(principal.user(), id));
    }

    @Post("/{id}/members")
    public HttpResponse<Dtos.MemberDto> invite(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            String id,
            @Body Requests.InviteRequest body) {
        return HttpResponse.created(channelService.invite(principal.user(), id, body.email()));
    }

    @Delete("/{id}/members/{memberId}")
    public HttpResponse<Void> removeMember(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String id, String memberId) {
        channelService.removeMember(principal.user(), id, memberId);
        return HttpResponse.noContent();
    }
}

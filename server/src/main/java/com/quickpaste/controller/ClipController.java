package com.quickpaste.controller;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.model.dto.Requests;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.service.ClipService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.Optional;

/** Clip endpoints, scoped to a channel (SPEC §8.2). */
@Controller("/api/channels/{cid}/clips")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ClipController {

    private final ClipService clipService;

    public ClipController(ClipService clipService) {
        this.clipService = clipService;
    }

    @Post
    public HttpResponse<Dtos.ClipDto> create(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            String cid,
            @Body Requests.CreateClipRequest body) {
        ClipService.CreateResult result = clipService.create(principal.user(), cid, body);
        // FR-18: a dedup no-op returns the existing clip with 200 instead of 201.
        return result.created() ? HttpResponse.created(result.clip()) : HttpResponse.ok(result.clip());
    }

    @Get("/latest")
    public HttpResponse<Dtos.ClipDto> latest(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String cid) {
        return clipService.latest(principal.user(), cid)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::noContent);
    }

    @Get
    public Dtos.ClipListResponse list(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            String cid,
            @QueryValue Optional<String> afterId,
            @QueryValue Optional<String> beforeId,
            @QueryValue Optional<Integer> limit,
            @QueryValue Optional<String> type) {
        return clipService.list(principal.user(), cid,
                afterId.orElse(null), beforeId.orElse(null), limit.orElse(null), type.orElse(null));
    }

    @Delete("/{id}")
    public HttpResponse<Void> delete(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String cid, String id) {
        clipService.delete(principal.user(), cid, id);
        return HttpResponse.noContent();
    }

    @Delete
    public HttpResponse<Void> clear(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String cid) {
        clipService.clear(principal.user(), cid);
        return HttpResponse.noContent();
    }
}

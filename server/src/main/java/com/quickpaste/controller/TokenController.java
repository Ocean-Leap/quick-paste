package com.quickpaste.controller;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.model.dto.Requests;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.security.TokenService;
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

/** API-token management (FR-4, SPEC §9.3): the secret is returned exactly once. */
@Controller("/api/tokens")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Post
    public HttpResponse<Dtos.TokenCreatedDto> create(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            @Body Requests.CreateTokenRequest body) {
        TokenService.MintedToken minted = tokenService.mint(principal.user(), body.name(), body.scope());
        return HttpResponse.created(new Dtos.TokenCreatedDto(
                minted.entity().id(), minted.plainToken(), minted.entity().prefix()));
    }

    @Get
    public Map<String, List<Dtos.TokenDto>> list(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal) {
        return Map.of("items", tokenService.list(principal.user()).stream().map(Dtos.TokenDto::from).toList());
    }

    @Delete("/{id}")
    public HttpResponse<Void> revoke(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String id) {
        tokenService.revoke(principal.user(), id);
        return HttpResponse.noContent();
    }
}

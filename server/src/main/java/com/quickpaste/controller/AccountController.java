package com.quickpaste.controller;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.security.SessionService;
import com.quickpaste.service.AccountService;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Account lifecycle endpoints (SPEC §5.12, §8.2). */
@Controller("/api/account")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AccountController {

    private final AccountService accountService;
    private final SessionService sessionService;

    public AccountController(AccountService accountService, SessionService sessionService) {
        this.accountService = accountService;
        this.sessionService = sessionService;
    }

    @Post("/logout-all")
    public HttpResponse<Void> logoutAll(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal) {
        accountService.logoutAll(principal.user());
        return HttpResponse.noContent();
    }

    @Delete
    public HttpResponse<Void> deleteAccount(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal) {
        sessionService.revokeAllForUser(principal.user().id());
        accountService.deleteUser(principal.user());
        return HttpResponse.noContent();
    }

    @Post("/export")
    public HttpResponse<Dtos.ExportJobDto> requestExport(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal) {
        return HttpResponse.status(HttpStatus.ACCEPTED)
                .body(accountService.requestExport(principal.user()));
    }

    @Get("/export/{jobId}")
    public Dtos.ExportJobDto exportStatus(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String jobId) {
        return accountService.exportStatus(principal.user(), jobId);
    }

    @Get("/export/{jobId}/download")
    public HttpResponse<StreamedFile> exportDownload(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String jobId) throws IOException {
        Path archive = accountService.exportArchive(principal.user(), jobId);
        return HttpResponse.ok(new StreamedFile(Files.newInputStream(archive), MediaType.of("application/zip")))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"quick-paste-export-" + jobId + ".zip\"");
    }
}

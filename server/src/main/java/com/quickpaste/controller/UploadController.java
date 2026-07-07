package com.quickpaste.controller;

import com.quickpaste.error.ApiException;
import com.quickpaste.model.UploadSession;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.service.UploadService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.io.InputStream;

/**
 * tus-1.0-compatible resumable uploads (SPEC §8.6). The finalized fileId equals the
 * uploadId from the creation Location, so clients can create the clip immediately
 * after the last PATCH.
 */
@Controller("/api/channels/{cid}/files/uploads")
@ExecuteOn(TaskExecutors.BLOCKING)
public class UploadController {

    private static final String TUS_VERSION = "1.0.0";

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @Post
    public HttpResponse<Dtos.UploadCreatedDto> create(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            String cid,
            @Header("Upload-Length") @Nullable String uploadLength,
            @Header("Upload-Metadata") @Nullable String uploadMetadata) {
        UploadSession session = uploadService.create(
                principal.user(), cid, parseLong(uploadLength, "Upload-Length"), uploadMetadata);
        return tus(HttpResponse.created(new Dtos.UploadCreatedDto(session.id())))
                .header("Location", "/api/channels/" + cid + "/files/uploads/" + session.id());
    }

    @Head("/{id}")
    public HttpResponse<Void> head(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String cid, String id) {
        UploadService.UploadState state = uploadService.state(principal.user(), cid, id);
        return tus(HttpResponse.<Void>ok())
                .header("Upload-Offset", String.valueOf(state.offset()))
                .header("Upload-Length", String.valueOf(state.session().declaredSize()))
                .header("Cache-Control", "no-store");
    }

    @Patch(uri = "/{id}", consumes = "application/offset+octet-stream")
    public HttpResponse<Void> patch(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            String cid,
            String id,
            @Header("Upload-Offset") @Nullable String uploadOffset,
            @Body InputStream body) {
        UploadService.PatchResult result = uploadService.appendChunk(
                principal.user(), cid, id, parseLong(uploadOffset, "Upload-Offset"), body);
        return tus(HttpResponse.<Void>noContent())
                .header("Upload-Offset", String.valueOf(result.newOffset()));
    }

    private static long parseLong(@Nullable String value, String headerName) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(headerName + " header is required.");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw ApiException.validation(headerName + " must be an integer.");
        }
    }

    private static <T> MutableHttpResponse<T> tus(MutableHttpResponse<T> response) {
        return response.header("Tus-Resumable", TUS_VERSION);
    }
}

package com.quickpaste.controller;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.service.FileService;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** File endpoints, scoped to a channel (SPEC §8.2): simple upload, download, metadata. */
@Controller("/api/channels/{cid}/files")
@ExecuteOn(TaskExecutors.BLOCKING)
public class FileController {

    private final FileService fileService;
    private final com.quickpaste.service.ThumbnailService thumbnailService;

    public FileController(FileService fileService,
                          com.quickpaste.service.ThumbnailService thumbnailService) {
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
    }

    @Post(consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<Dtos.FileUploadedDto> upload(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            String cid,
            CompletedFileUpload file) {
        return HttpResponse.created(fileService.upload(principal.user(), cid, file));
    }

    @Get("/{id}")
    public HttpResponse<StreamedFile> download(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String cid, String id)
            throws IOException {
        FileService.Download download = fileService.download(principal.user(), cid, id);
        MediaType mediaType;
        try {
            mediaType = MediaType.of(download.file().mime());
        } catch (IllegalArgumentException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        InputStream in = Files.newInputStream(download.path());
        return HttpResponse.ok(new StreamedFile(in, mediaType))
                .header(HttpHeaders.CONTENT_DISPOSITION, FileService.contentDisposition(download.file().name()));
    }

    @Get("/{id}/meta")
    public Dtos.FileMetaDto meta(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String cid, String id) {
        return fileService.meta(principal.user(), cid, id);
    }

    /** FR-23: lazy JPEG thumbnail for image files; 404 for non-images or when disabled. */
    @Get("/{id}/thumbnail")
    public HttpResponse<StreamedFile> thumbnail(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String cid, String id)
            throws IOException {
        var file = fileService.liveFile(principal.user(), cid, id);
        var thumb = thumbnailService.thumbnailFor(file)
                .orElseThrow(com.quickpaste.error.ApiException::notFound);
        return HttpResponse.ok(new StreamedFile(Files.newInputStream(thumb), MediaType.IMAGE_JPEG_TYPE))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
    }
}

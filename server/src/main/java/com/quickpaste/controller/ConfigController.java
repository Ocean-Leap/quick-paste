package com.quickpaste.controller;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.model.dto.Dtos;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/** Client-facing limits + push capability (authenticated; SPEC §8.2). */
@Controller("/api/config")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ConfigController {

    private final QuickPasteConfig config;

    public ConfigController(QuickPasteConfig config) {
        this.config = config;
    }

    @Get
    public Dtos.ClientConfigDto get() {
        return new Dtos.ClientConfigDto(
                config.getMaxTextKb(),
                config.getSimpleUploadMb(),
                config.getMaxFileMb(),
                config.getUserQuotaMb(),
                config.isThumbnailsEnabled(),
                config.pushEnabled(),
                config.pushEnabled() ? config.getVapidPublicKey() : null);
    }
}

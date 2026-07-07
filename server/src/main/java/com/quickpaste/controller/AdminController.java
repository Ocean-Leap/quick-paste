package com.quickpaste.controller;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.model.dto.Requests;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.service.AdminService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.List;
import java.util.Map;

/** Admin-only user management (FR-49, SPEC §8.2). */
@Controller("/api/admin/users")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @Get
    public Map<String, List<Dtos.AdminUserDto>> list(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal) {
        return Map.of("items", adminService.listUsers(principal.user()));
    }

    @Patch("/{id}")
    public Dtos.AdminUserDto setStatus(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            String id,
            @Body Requests.PatchUserStatusRequest body) {
        return adminService.setStatus(principal.user(), id, body.status());
    }

    @Delete("/{id}")
    public HttpResponse<Void> delete(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String id) {
        adminService.deleteUser(principal.user(), id);
        return HttpResponse.noContent();
    }
}

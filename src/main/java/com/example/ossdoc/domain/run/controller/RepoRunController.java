// domain/run/controller/RepoRunController.java
package com.example.ossdoc.domain.run.controller;

import com.example.ossdoc.domain.run.dto.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.service.RepoRunService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/runs")
public class RepoRunController {

    private final RepoRunService repoRunService;

    @PostMapping
    public ApiResponse<RepoRunCreateResponse> create(@Valid @RequestBody RepoRunCreateRequest request) {
        RepoRunCreateResponse response = repoRunService.createRun(request);
        return ApiResponse.onSuccess(response);
    }
}
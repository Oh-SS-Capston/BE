package com.example.ossdoc.domain.graphstore.controller;

import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.graphstore.dto.request.GraphStoreIngestRequest;
import com.example.ossdoc.domain.graphstore.dto.response.GraphStoreIngestResponse;
import com.example.ossdoc.domain.graphstore.service.GraphStoreIngestService;
import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/graphstore")
public class GraphStoreController {
    private final GraphStoreIngestService graphStoreIngestService;

    @PostMapping("/ingest")
    public ApiResponse<GraphStoreIngestResponse> ingest(@Valid @RequestBody GraphStoreIngestRequest request, @AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        if (authenticatedUser == null) {
            throw new AuthException(AuthErrorCode.UNAUTHORIZED_USER);
        }

        GraphStoreIngestResponse response =
                graphStoreIngestService.ingest(request, authenticatedUser.getUserId());

        return ApiResponse.onSuccess(response);
    }
}
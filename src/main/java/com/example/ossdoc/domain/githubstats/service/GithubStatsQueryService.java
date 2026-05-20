package com.example.ossdoc.domain.githubstats.service;

import com.example.ossdoc.domain.githubstats.dto.response.GithubStatsResponse;
import com.example.ossdoc.domain.githubstats.entity.GithubStatsSnapshot;
import com.example.ossdoc.domain.githubstats.exception.GithubStatsException;
import com.example.ossdoc.domain.githubstats.exception.code.GithubStatsErrorCode;
import com.example.ossdoc.domain.githubstats.repository.GithubStatsSnapshotRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.global.properties.GithubStatsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GithubStatsQueryService {

    private final RepoRunRepository repoRunRepository;
    private final GithubStatsSnapshotRepository snapshotRepository;
    private final GithubStatsProperties properties;
    private final ObjectMapper objectMapper;

    public RepoRun findRunAndCheckOwner(String runId, Long userId) {
        RepoRun run = repoRunRepository.findById(runId)
                .orElseThrow(() -> new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_RUN_NOT_FOUND));

        if (run.getOwner() == null || !Objects.equals(run.getOwner().getId(), userId)) {
            throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_FORBIDDEN);
        }

        return run;
    }

    public Optional<GithubStatsResponse> findValidCache(String runId) {
        List<GithubStatsSnapshot> snapshots =
                snapshotRepository.findLatestByRunId(runId, PageRequest.of(0, 1));

        if (snapshots.isEmpty()) {
            return Optional.empty();
        }

        GithubStatsSnapshot snapshot = snapshots.get(0);

        if (!isCacheAlive(snapshot) || !isIssueActivityCache(snapshot.getPayload())) {
            return Optional.empty();
        }

        try {
            GithubStatsResponse response = objectMapper.treeToValue(
                    snapshot.getPayload(),
                    GithubStatsResponse.class
            );

            return Optional.of(
                    response.toBuilder()
                            .fromCache(true)
                            .collectedAt(snapshot.getCollectedAt())
                            .build()
            );

        } catch (Exception e) {
            log.warn(
                    "GitHub stats cache read failed runId={}, snapshotId={}",
                    runId,
                    snapshot.getId(),
                    e
            );

            throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_CACHE_READ_FAILED);
        }
    }

    private boolean isIssueActivityCache(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return false;
        }

        JsonNode summary = payload.path("summary");
        JsonNode activity = payload.path("activity");

        return summary.has("recent28dClosedIssues")
                && activity.has("recent28dDailyIssueActivities");
    }

    private boolean isCacheAlive(GithubStatsSnapshot snapshot) {
        long ttlMinutes = properties.getCacheTtlMinutes() <= 0
                ? 360
                : properties.getCacheTtlMinutes();

        return snapshot.getCollectedAt()
                .plusMinutes(ttlMinutes)
                .isAfter(LocalDateTime.now());
    }
}
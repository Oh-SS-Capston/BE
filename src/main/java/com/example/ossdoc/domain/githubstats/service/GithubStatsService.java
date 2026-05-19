package com.example.ossdoc.domain.githubstats.service;

import com.example.ossdoc.domain.githubstats.client.GithubStatsApiClient;
import com.example.ossdoc.domain.githubstats.dto.response.GithubStatsResponse;
import com.example.ossdoc.domain.githubstats.exception.GithubStatsException;
import com.example.ossdoc.domain.githubstats.exception.code.GithubStatsErrorCode;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.support.GithubRepoRef;
import com.example.ossdoc.domain.run.support.GithubUrlParser;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubStatsService {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final int RECENT_DAYS = 28;

    private final GithubStatsQueryService githubStatsQueryService;
    private final GithubStatsSnapshotCommandService snapshotCommandService;
    private final GithubStatsApiClient githubStatsApiClient;
    private final GithubStatsInsightBuilder insightBuilder;

    public GithubStatsResponse getStats(String runId, Long userId, boolean forceRefresh) {
        RepoRun run = githubStatsQueryService.findRunAndCheckOwner(runId, userId);

        if (!forceRefresh) {
            Optional<GithubStatsResponse> cached = githubStatsQueryService.findValidCache(runId);

            if (cached.isPresent()) {
                return cached.get();
            }
        }

        return collectAndSave(run);
    }

    private GithubStatsResponse collectAndSave(RepoRun run) {
        GithubRepoRef repoRef = resolveRepoRef(run);

        String owner = repoRef.getOwner();
        String repo = repoRef.getRepo();

        /*
         * 저장소 기본 정보는 화면 구성에 필요한 필수 데이터입니다.
         * contributors/search 계열 API는 rate limit이나 일시 실패가 발생할 수 있으므로
         * 실패해도 전체 화면이 깨지지 않도록 null 또는 빈 데이터로 대체합니다.
         */
        JsonNode repositoryNode = githubStatsApiClient.getRepository(owner, repo);
        String defaultBranch = text(repositoryNode, "default_branch", null);

        JsonNode languagesNode = getOptionalJson("languages", () -> githubStatsApiClient.getLanguages(owner, repo));
        JsonNode latestReleaseNode = githubStatsApiClient.getLatestReleaseOrNull(owner, repo);

        Long contributors = getOptionalLong("contributors", () -> githubStatsApiClient.countContributors(owner, repo));

        LocalDate recentStartDate = LocalDate.now(UTC).minusDays(RECENT_DAYS - 1L);

        Map<LocalDate, Integer> recentIssueCounts = getOptionalDailyCountMap(
                "recent_issues_created_by_date",
                () -> githubStatsApiClient.countRecentIssuesByDate(owner, repo, recentStartDate)
        );

        Map<LocalDate, Integer> recentClosedIssueCounts = getOptionalDailyCountMap(
                "recent_issues_closed_by_date",
                () -> githubStatsApiClient.countRecentClosedIssuesByDate(owner, repo, recentStartDate)
        );

        Long recentIssues = recentIssueCounts == null
                ? getOptionalLong("recent_issues_created", () -> githubStatsApiClient.countRecentIssues(owner, repo, recentStartDate))
                : recentIssueCounts.values().stream().mapToLong(Integer::longValue).sum();

        Long recentClosedIssues = recentClosedIssueCounts == null
                ? getOptionalLong("recent_issues_closed", () -> githubStatsApiClient.countRecentClosedIssues(owner, repo, recentStartDate))
                : recentClosedIssueCounts.values().stream().mapToLong(Integer::longValue).sum();

        IssueActivityResult issueActivity = buildRecentIssueActivity(
                recentStartDate,
                recentIssueCounts,
                recentClosedIssueCounts
        );

        String fullName = text(repositoryNode, "full_name", owner + "/" + repo);
        String primaryLanguage = text(repositoryNode, "language", null);
        Double languagePercent = calculateLanguagePercent(languagesNode, primaryLanguage);

        Long stars = longValue(repositoryNode, "stargazers_count");
        Long forks = longValue(repositoryNode, "forks_count");
        Long openIssues = longValue(repositoryNode, "open_issues_count");

        LocalDateTime collectedAt = LocalDateTime.now();

        GithubStatsResponse response = GithubStatsResponse.builder()
                .runId(run.getRunId())
                .repository(GithubStatsResponse.RepositoryInfo.builder()
                        .fullName(fullName)
                        .owner(owner)
                        .name(repo)
                        .description(text(repositoryNode, "description", ""))
                        .htmlUrl(text(repositoryNode, "html_url", ""))
                        .avatarUrl(text(repositoryNode.path("owner"), "avatar_url", null))
                        .language(primaryLanguage)
                        .languagePercent(languagePercent)
                        .defaultBranch(defaultBranch == null ? "" : defaultBranch)
                        .license(resolveLicense(repositoryNode))
                        .createdAt(text(repositoryNode, "created_at", null))
                        .updatedAt(text(repositoryNode, "updated_at", null))
                        .pushedAt(text(repositoryNode, "pushed_at", null))
                        .latestRelease(text(latestReleaseNode, "tag_name", null))
                        .latestReleasePublishedAt(text(latestReleaseNode, "published_at", null))
                        .build())
                .summary(GithubStatsResponse.Summary.builder()
                        .stars(stars)
                        .forks(forks)
                        .openIssues(openIssues)
                        .recent28dIssues(recentIssues)
                        .recent28dClosedIssues(recentClosedIssues)
                        .contributors(contributors)
                        .starDelta28d(null)
                        .forkDelta28d(null)
                        .openIssueDelta28d(null)
                        .contributorDelta28d(null)
                        .build())
                .activity(GithubStatsResponse.Activity.builder()
                        .recent28dDailyIssueActivities(issueActivity.dailyActivities())
                        .build())
                .insights(insightBuilder.build(stars, recentClosedIssues, contributors))
                .collectedAt(collectedAt)
                .fromCache(false)
                .build();

        snapshotCommandService.saveSnapshot(run.getRunId(), response);

        return response;
    }

    private GithubRepoRef resolveRepoRef(RepoRun run) {
        if (notBlank(run.getRepoOwner()) && notBlank(run.getRepoName())) {
            return GithubRepoRef.builder()
                    .owner(run.getRepoOwner())
                    .repo(run.getRepoName())
                    .build();
        }

        try {
            return GithubUrlParser.parse(run.getRepoUrl(), null);
        } catch (Exception e) {
            throw new GithubStatsException(GithubStatsErrorCode.GITHUB_STATS_INVALID_REPOSITORY);
        }
    }

    private IssueActivityResult buildRecentIssueActivity(
            LocalDate recentStartDate,
            Map<LocalDate, Integer> dailyIssueCounts,
            Map<LocalDate, Integer> dailyClosedIssueCounts
    ) {
        boolean hasIssueCounts = dailyIssueCounts != null;
        boolean hasClosedIssueCounts = dailyClosedIssueCounts != null;

        if (!hasIssueCounts && !hasClosedIssueCounts) {
            return new IssueActivityResult(List.of());
        }

        List<GithubStatsResponse.DailyIssueActivity> daily = new ArrayList<>();

        for (int i = 0; i < RECENT_DAYS; i++) {
            LocalDate date = recentStartDate.plusDays(i);

            Integer issuesCreated = hasIssueCounts ? dailyIssueCounts.getOrDefault(date, 0) : 0;
            Integer issuesClosed = hasClosedIssueCounts ? dailyClosedIssueCounts.getOrDefault(date, 0) : 0;

            daily.add(dailyIssueActivity(date, issuesCreated, issuesClosed));
        }

        return new IssueActivityResult(daily);
    }

    private JsonNode getOptionalJson(String apiName, Supplier<JsonNode> supplier) {
        try {
            return supplier.get();
        } catch (GithubStatsException e) {
            log.warn("GitHub optional API skipped apiName={}, code={}", apiName, e.getCode().getReason().getCode());
            return null;
        } catch (Exception e) {
            log.warn("GitHub optional API skipped apiName={}, cause={}", apiName, e.toString());
            return null;
        }
    }

    private Long getOptionalLong(String apiName, Supplier<Long> supplier) {
        try {
            return supplier.get();
        } catch (GithubStatsException e) {
            log.warn("GitHub optional API skipped apiName={}, code={}", apiName, e.getCode().getReason().getCode());
            return null;
        } catch (Exception e) {
            log.warn("GitHub optional API skipped apiName={}, cause={}", apiName, e.toString());
            return null;
        }
    }

    private Map<LocalDate, Integer> getOptionalDailyCountMap(
            String apiName,
            Supplier<Map<LocalDate, Integer>> supplier
    ) {
        try {
            return supplier.get();
        } catch (GithubStatsException e) {
            log.warn("GitHub optional API skipped apiName={}, code={}", apiName, e.getCode().getReason().getCode());
            return null;
        } catch (Exception e) {
            log.warn("GitHub optional API skipped apiName={}, cause={}", apiName, e.toString());
            return null;
        }
    }

    private GithubStatsResponse.DailyIssueActivity dailyIssueActivity(
            LocalDate date,
            Integer issuesCreated,
            Integer issuesClosed
    ) {
        return GithubStatsResponse.DailyIssueActivity.builder()
                .date(date.toString())
                .issuesCreated(issuesCreated)
                .issuesClosed(issuesClosed)
                .build();
    }

    private Double calculateLanguagePercent(JsonNode languagesNode, String primaryLanguage) {
        if (languagesNode == null || primaryLanguage == null || primaryLanguage.isBlank()) {
            return null;
        }

        long totalBytes = 0;

        Iterator<Map.Entry<String, JsonNode>> fields = languagesNode.fields();

        while (fields.hasNext()) {
            totalBytes += fields.next().getValue().asLong(0L);
        }

        if (totalBytes <= 0 || languagesNode.get(primaryLanguage) == null) {
            return null;
        }

        long primaryBytes = languagesNode.get(primaryLanguage).asLong(0L);
        double percent = (primaryBytes * 100.0) / totalBytes;

        return Math.round(percent * 10.0) / 10.0;
    }

    private String resolveLicense(JsonNode repositoryNode) {
        JsonNode licenseNode = repositoryNode == null ? null : repositoryNode.get("license");

        if (licenseNode == null || licenseNode.isNull()) {
            return null;
        }

        String spdxId = text(licenseNode, "spdx_id", null);

        if (notBlank(spdxId) && !"NOASSERTION".equalsIgnoreCase(spdxId)) {
            return spdxId;
        }

        return text(licenseNode, "name", null);
    }

    private String text(JsonNode node, String fieldName, String defaultValue) {
        if (node == null || node.get(fieldName) == null || node.get(fieldName).isNull()) {
            return defaultValue;
        }

        return node.get(fieldName).asText(defaultValue);
    }

    private Long longValue(JsonNode node, String fieldName) {
        if (node == null || node.get(fieldName) == null || node.get(fieldName).isNull()) {
            return null;
        }

        return node.get(fieldName).asLong();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record IssueActivityResult(
            List<GithubStatsResponse.DailyIssueActivity> dailyActivities
    ) {
    }
}

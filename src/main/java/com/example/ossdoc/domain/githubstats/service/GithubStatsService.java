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

        JsonNode repositoryNode = githubStatsApiClient.getRepository(owner, repo);
        JsonNode languagesNode = githubStatsApiClient.getLanguages(owner, repo);
        JsonNode commitActivityNode = githubStatsApiClient.getCommitActivityOrNull(owner, repo);
        JsonNode latestReleaseNode = githubStatsApiClient.getLatestReleaseOrNull(owner, repo);

        Long contributors = githubStatsApiClient.countContributors(owner, repo);

        LocalDate recentStartDate = LocalDate.now(UTC).minusDays(RECENT_DAYS - 1L);
        Long recentIssues = githubStatsApiClient.countRecentIssues(owner, repo, recentStartDate);

        CommitActivityResult commitActivity = buildCommitActivity(commitActivityNode, recentStartDate);

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
                        .language(primaryLanguage)
                        .languagePercent(languagePercent)
                        .defaultBranch(text(repositoryNode, "default_branch", ""))
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
                        .recent28dCommits(commitActivity.recent28dCommits())
                        .recent28dIssues(recentIssues)
                        .contributors(contributors)
                        .starDelta28d(null)
                        .forkDelta28d(null)
                        .openIssueDelta28d(null)
                        .contributorDelta28d(null)
                        .build())
                .activity(GithubStatsResponse.Activity.builder()
                        .commitStatsProcessing(commitActivity.processing())
                        .recent28dDailyActivities(commitActivity.dailyActivities())
                        .lastYearWeeklyCommits(commitActivity.weeklyActivities())
                        .build())
                .insights(insightBuilder.build(stars, commitActivity.recent28dCommits(), contributors))
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

    private CommitActivityResult buildCommitActivity(JsonNode commitActivityNode, LocalDate recentStartDate) {
        LocalDate today = LocalDate.now(UTC);

        Map<LocalDate, Integer> recentCommitCounts = new LinkedHashMap<>();

        for (int i = 0; i < RECENT_DAYS; i++) {
            recentCommitCounts.put(recentStartDate.plusDays(i), 0);
        }

        List<GithubStatsResponse.WeeklyCommitActivity> weeklyActivities = new ArrayList<>();

        if (commitActivityNode == null || !commitActivityNode.isArray()) {
            List<GithubStatsResponse.DailyActivity> daily = recentCommitCounts.entrySet()
                    .stream()
                    .map(entry -> dailyActivity(entry.getKey(), null, null))
                    .toList();

            return new CommitActivityResult(true, null, daily, weeklyActivities);
        }

        for (JsonNode weekNode : commitActivityNode) {
            long weekEpochSeconds = weekNode.path("week").asLong(0L);

            LocalDate weekStart = Instant.ofEpochSecond(weekEpochSeconds)
                    .atZone(UTC)
                    .toLocalDate();

            int weekTotal = weekNode.path("total").asInt(0);

            weeklyActivities.add(GithubStatsResponse.WeeklyCommitActivity.builder()
                    .weekStart(weekStart.toString())
                    .commits(weekTotal)
                    .build());

            JsonNode daysNode = weekNode.path("days");

            if (!daysNode.isArray()) {
                continue;
            }

            for (int i = 0; i < daysNode.size(); i++) {
                LocalDate date = weekStart.plusDays(i);

                if (date.isBefore(recentStartDate) || date.isAfter(today)) {
                    continue;
                }

                recentCommitCounts.put(date, daysNode.get(i).asInt(0));
            }
        }

        int recentTotal = recentCommitCounts.values()
                .stream()
                .mapToInt(Integer::intValue)
                .sum();

        List<GithubStatsResponse.DailyActivity> daily = recentCommitCounts.entrySet()
                .stream()
                .map(entry -> dailyActivity(entry.getKey(), entry.getValue(), null))
                .toList();

        return new CommitActivityResult(false, recentTotal, daily, weeklyActivities);
    }

    private GithubStatsResponse.DailyActivity dailyActivity(
            LocalDate date,
            Integer commits,
            Integer issuesCreated
    ) {
        return GithubStatsResponse.DailyActivity.builder()
                .date(date.toString())
                .commits(commits)
                .issuesCreated(issuesCreated)
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

    private record CommitActivityResult(
            boolean processing,
            Integer recent28dCommits,
            List<GithubStatsResponse.DailyActivity> dailyActivities,
            List<GithubStatsResponse.WeeklyCommitActivity> weeklyActivities
    ) {
    }
}
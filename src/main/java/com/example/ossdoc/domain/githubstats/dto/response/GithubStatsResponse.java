package com.example.ossdoc.domain.githubstats.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder(toBuilder = true)
@Jacksonized
public class GithubStatsResponse {

    private final String runId;
    private final RepositoryInfo repository;
    private final Summary summary;
    private final Activity activity;
    private final List<Insight> insights;
    private final LocalDateTime collectedAt;
    private final Boolean fromCache;

    @Getter
    @Builder
    @Jacksonized
    public static class RepositoryInfo {
        private final String fullName;
        private final String owner;
        private final String name;
        private final String description;
        private final String htmlUrl;
        private final String avatarUrl;
        private final String language;
        private final Double languagePercent;
        private final String defaultBranch;
        private final String license;
        private final String createdAt;
        private final String updatedAt;
        private final String pushedAt;
        private final String latestRelease;
        private final String latestReleasePublishedAt;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class Summary {
        private final Long stars;
        private final Long forks;
        private final Long openIssues;
        private final Long recent28dIssues;
        private final Long recent28dClosedIssues;
        private final Long contributors;

        /*
         * 현재 버전에서는 snapshot이 충분히 쌓였을 때만 계산합니다.
         * 값이 null이면 FE에서 증가량 문구를 숨기거나 "-"로 표시합니다.
         */
        private final Long starDelta28d;
        private final Long forkDelta28d;
        private final Long openIssueDelta28d;
        private final Long contributorDelta28d;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class Activity {
        private final List<DailyIssueActivity> recent28dDailyIssueActivities;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class DailyIssueActivity {
        private final String date;
        private final Integer issuesCreated;
        private final Integer issuesClosed;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class Insight {
        private final String type;
        private final String title;
        private final String message;
    }
}

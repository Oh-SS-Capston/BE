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
        private final Integer recent28dCommits;
        private final Long recent28dIssues;
        private final Long contributors;

        /*
         * GitHub API만으로 과거 대비 증가량을 안정적으로 계산하기 어렵기 때문에,
         * 초기 버전에서는 null로 내려주고 FE에서 숨김 처리합니다.
         * 추후 우리 DB에 일별 snapshot을 쌓으면 값 채우면 됩니다.
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
        private final Boolean commitStatsProcessing;
        private final List<DailyActivity> recent28dDailyActivities;
        private final List<WeeklyCommitActivity> lastYearWeeklyCommits;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class DailyActivity {
        private final String date;
        private final Integer commits;
        private final Integer issuesCreated;
    }

    @Getter
    @Builder
    @Jacksonized
    public static class WeeklyCommitActivity {
        private final String weekStart;
        private final Integer commits;
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
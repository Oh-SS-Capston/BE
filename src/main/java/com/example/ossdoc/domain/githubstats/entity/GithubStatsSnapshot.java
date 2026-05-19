package com.example.ossdoc.domain.githubstats.entity;

import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "github_stats_snapshot",
        indexes = {
                @Index(name = "idx_github_stats_run_collected", columnList = "run_id, collected_at"),
                @Index(name = "idx_github_stats_repo_collected", columnList = "repo_full_name, collected_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GithubStatsSnapshot extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "github_stats_snapshot_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @Column(name = "repo_full_name", nullable = false)
    private String repoFullName;

    @Column(name = "stars")
    private Long stars;

    @Column(name = "forks")
    private Long forks;

    @Column(name = "open_issues")
    private Long openIssues;

    @Column(name = "contributors")
    private Long contributors;

    @Column(name = "recent_28d_issues")
    private Long recent28dIssues;

    @Column(name = "recent_28d_closed_issues")
    private Long recent28dClosedIssues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private JsonNode payload;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    public static GithubStatsSnapshot create(
            RepoRun run,
            String repoFullName,
            Long stars,
            Long forks,
            Long openIssues,
            Long contributors,
            Long recent28dIssues,
            Long recent28dClosedIssues,
            JsonNode payload,
            LocalDateTime collectedAt
    ) {
        GithubStatsSnapshot snapshot = new GithubStatsSnapshot();
        snapshot.run = run;
        snapshot.repoFullName = repoFullName;
        snapshot.stars = stars;
        snapshot.forks = forks;
        snapshot.openIssues = openIssues;
        snapshot.contributors = contributors;
        snapshot.recent28dIssues = recent28dIssues;
        snapshot.recent28dClosedIssues = recent28dClosedIssues;
        snapshot.payload = payload;
        snapshot.collectedAt = collectedAt;
        return snapshot;
    }
}

package com.example.ossdoc.domain.githubstats.service;

import com.example.ossdoc.domain.githubstats.dto.response.GithubStatsResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class GithubStatsInsightBuilder {

    public List<GithubStatsResponse.Insight> build(
            Long stars,
            Long recent28dClosedIssues,
            Long contributors
    ) {
        List<GithubStatsResponse.Insight> insights = new ArrayList<>();

        insights.add(popularityInsight(stars));
        insights.add(maintenanceInsight(recent28dClosedIssues));
        insights.add(communityInsight(contributors));

        return insights;
    }

    private GithubStatsResponse.Insight popularityInsight(Long stars) {
        long value = stars == null ? 0 : stars;

        if (value >= 10_000) {
            return insight(
                    "POPULARITY",
                    "높은 인기",
                    "스타 수가 높아 많은 사용자가 관심을 가지고 있는 저장소입니다."
            );
        }

        if (value >= 1_000) {
            return insight(
                    "POPULARITY",
                    "일정 수준의 인기",
                    "스타 수를 기준으로 일정 규모 이상의 관심을 받고 있는 저장소입니다."
            );
        }

        return insight(
                "POPULARITY",
                "낮은 인지도",
                "스타 수가 아직 많지 않아 도입 전 추가 검토가 필요합니다."
        );
    }

    private GithubStatsResponse.Insight maintenanceInsight(Long recent28dClosedIssues) {
        if (recent28dClosedIssues == null) {
            return insight(
                    "MAINTENANCE",
                    "이슈 해결 수집 실패",
                    "최근 28일 해결 이슈를 수집하지 못했습니다. GitHub API 상태를 확인해주세요."
            );
        }

        if (recent28dClosedIssues >= 100) {
            return insight(
                    "MAINTENANCE",
                    "활발한 이슈 대응",
                    "최근 28일 동안 해결된 이슈가 많아 유지보수 대응이 활발합니다."
            );
        }

        if (recent28dClosedIssues >= 20) {
            return insight(
                    "MAINTENANCE",
                    "이슈 대응 진행 중",
                    "최근 28일 동안 일정 수준의 이슈 해결 활동이 확인됩니다."
            );
        }

        return insight(
                "MAINTENANCE",
                "낮은 최근 이슈 해결",
                "최근 28일 해결 이슈 수가 적어 유지보수 상태를 추가로 확인하는 것이 좋습니다."
        );
    }

    private GithubStatsResponse.Insight communityInsight(Long contributors) {
        long value = contributors == null ? 0 : contributors;

        if (value >= 100) {
            return insight(
                    "COMMUNITY",
                    "넓은 커뮤니티",
                    "많은 기여자가 참여하고 있어 커뮤니티 규모가 큰 저장소입니다."
            );
        }

        if (value >= 20) {
            return insight(
                    "COMMUNITY",
                    "일정 규모의 커뮤니티",
                    "여러 기여자가 참여하고 있어 협업 이력이 확인됩니다."
            );
        }

        return insight(
                "COMMUNITY",
                "작은 커뮤니티",
                "기여자 수가 많지 않아 장기 유지보수 가능성을 함께 확인해야 합니다."
        );
    }

    private GithubStatsResponse.Insight insight(String type, String title, String message) {
        return GithubStatsResponse.Insight.builder()
                .type(type)
                .title(title)
                .message(message)
                .build();
    }
}

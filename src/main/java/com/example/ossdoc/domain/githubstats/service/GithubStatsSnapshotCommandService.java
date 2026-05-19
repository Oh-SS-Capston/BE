package com.example.ossdoc.domain.githubstats.service;

import com.example.ossdoc.domain.githubstats.dto.response.GithubStatsResponse;
import com.example.ossdoc.domain.githubstats.entity.GithubStatsSnapshot;
import com.example.ossdoc.domain.githubstats.repository.GithubStatsSnapshotRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GithubStatsSnapshotCommandService {

    private final RepoRunRepository repoRunRepository;
    private final GithubStatsSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveSnapshot(String runId, GithubStatsResponse response) {
        RepoRun runRef = repoRunRepository.getReferenceById(runId);

        GithubStatsResponse.RepositoryInfo repository = response.getRepository();
        GithubStatsResponse.Summary summary = response.getSummary();

        JsonNode payload = objectMapper.valueToTree(response);

        snapshotRepository.save(GithubStatsSnapshot.create(
                runRef,
                repository.getFullName(),
                summary.getStars(),
                summary.getForks(),
                summary.getOpenIssues(),
                summary.getContributors(),
                summary.getRecent28dCommits(),
                summary.getRecent28dIssues(),
                payload,
                response.getCollectedAt()
        ));
    }
}
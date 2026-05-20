package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.artifact.repository.ArtifactRepository;
import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.cache.service.AnalysisCacheLookupService;
import com.example.ossdoc.domain.run.dto.request.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.response.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.entity.RunPipelineJob;
import com.example.ossdoc.domain.run.entity.RunPipelineStepExecution;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
import com.example.ossdoc.domain.run.repository.RunPipelineJobRepository;
import com.example.ossdoc.domain.run.repository.RunPipelineStepExecutionRepository;
import com.example.ossdoc.domain.run.support.GithubClient;
import com.example.ossdoc.domain.run.support.RunAnalysisCacheKeyFactory;
import com.example.ossdoc.domain.run.support.WorkspaceManager;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.enums.AuthProvider;
import com.example.ossdoc.domain.user.enums.UserRole;
import com.example.ossdoc.domain.user.repository.UserRepository;
import com.example.ossdoc.global.properties.AnalysisCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepoRunServiceTest {

    @Mock
    private RepoRunRepository repoRunRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GithubClient githubClient;
    @Mock
    private WorkspaceManager workspaceManager;
    @Mock
    private RunPipelineQueueService pipelineQueueService;
    @Mock
    private AnalysisCacheLookupService analysisCacheLookupService;
    @Mock
    private RunPipelineJobRepository runPipelineJobRepository;
    @Mock
    private RunPipelineStepExecutionRepository runPipelineStepExecutionRepository;
    @Mock
    private ArtifactRepository artifactRepository;

    private RepoRunService repoRunService;

    @BeforeEach
    void setUp() {
        AnalysisCacheProperties cacheProperties = new AnalysisCacheProperties();
        cacheProperties.setPipelineContractVersion("pipeline-v1");
        cacheProperties.setLlmProfileVersion("llm-profile-v1");
        cacheProperties.setPromptTemplateVersion("prompt-v1");
        cacheProperties.setOutputSchemaVersion("schema-v1");
        cacheProperties.setDefaultRunOptionsSignature("options-default-v1");

        repoRunService = new RepoRunService(
                repoRunRepository,
                userRepository,
                githubClient,
                workspaceManager,
                pipelineQueueService,
                new RunAnalysisCacheKeyFactory(),
                analysisCacheLookupService,
                cacheProperties,
                runPipelineJobRepository,
                runPipelineStepExecutionRepository,
                artifactRepository
        );
    }

    @Test
    void cache_hit이면_신규_분석을_실행하지_않고_기존_run을_즉시_반환한다() {
        Long userId = 1L;
        RepoRunCreateRequest request = request("https://github.com/apache/commons-cli", "master");
        User owner = user(userId);

        RepoRun cachedRun = new RepoRun(
                "run_cached_001",
                owner,
                "https://github.com/apache/commons-cli",
                "apache",
                "commons-cli",
                "master",
                "e717fd63",
                "C:/data/ossdoc/run_cached_001"
        );
        cachedRun.markSuccess();

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(githubClient.resolveCommitSha("apache", "commons-cli", "master")).thenReturn("e717fd63");
        when(analysisCacheLookupService.lookupReady(any(), eq("github://apache/commons-cli"), eq("e717fd63")))
                .thenReturn(AnalysisCacheLookupResult.hit("cache-key-1", "run_cached_001", "REDIS_HIT_DB_CONFIRMED"));
        when(repoRunRepository.findByRunIdAndOwner_Id("run_cached_001", userId))
                .thenReturn(Optional.of(cachedRun));
        when(runPipelineJobRepository.findByRun_RunId("run_cached_001"))
                .thenReturn(Optional.of(successJob(cachedRun, userId)));

        RepoRunCreateResponse response = repoRunService.createRun(request, userId);

        assertThat(response.getRunId()).isEqualTo("run_cached_001");
        assertThat(response.getCommitSha()).isEqualTo("e717fd63");
        assertThat(response.getWorkspaceRoot()).isEqualTo("C:/data/ossdoc/run_cached_001");
        assertThat(response.isCacheHit()).isTrue();
        assertThat(response.getCacheKey()).isEqualTo("cache-key-1");
        assertThat(response.getSourceRunId()).isEqualTo("run_cached_001");

        verify(repoRunRepository, never()).save(any(RepoRun.class));
        verify(pipelineQueueService, never()).enqueue(any(RepoRun.class), eq(userId));
    }

    @Test
    void cache_hit이지만_타사용자_run이면_요청자_소유_공유_run을_생성해_반환한다() {
        Long userId = 1L;
        RepoRunCreateRequest request = request("https://github.com/apache/commons-cli", "master");
        User requester = user(userId);

        User sourceOwner = user(2L);
        RepoRun sourceRun = new RepoRun(
                "run_source_001",
                sourceOwner,
                "https://github.com/apache/commons-cli",
                "apache",
                "commons-cli",
                "master",
                "e717fd63",
                "C:/data/ossdoc/run_source_001"
        );
        sourceRun.markSuccess();

        when(userRepository.findById(userId)).thenReturn(Optional.of(requester));
        when(githubClient.resolveCommitSha("apache", "commons-cli", "master")).thenReturn("e717fd63");
        when(analysisCacheLookupService.lookupReady(any(), eq("github://apache/commons-cli"), eq("e717fd63")))
                .thenReturn(AnalysisCacheLookupResult.hit("cache-key-1", "run_source_001", "REDIS_HIT_DB_CONFIRMED"));
        when(repoRunRepository.findByRunIdAndOwner_Id("run_source_001", userId))
                .thenReturn(Optional.empty());
        when(repoRunRepository.findById("run_source_001"))
                .thenReturn(Optional.of(sourceRun));
        when(repoRunRepository.save(any(RepoRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(runPipelineJobRepository.findByRun_RunId("run_source_001"))
                .thenReturn(Optional.of(successJob(sourceRun, userId)));
        when(runPipelineJobRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(runPipelineStepExecutionRepository.findAllByRun_RunIdOrderByStepIdAsc("run_source_001"))
                .thenReturn(List.of());
        when(artifactRepository.findAllByRun_RunIdOrderByArtifactIdAsc("run_source_001"))
                .thenReturn(List.of());

        RepoRunCreateResponse response = repoRunService.createRun(request, userId);

        assertThat(response.getRunId()).startsWith("run_");
        assertThat(response.getRunId()).isNotEqualTo("run_source_001");
        assertThat(response.getCommitSha()).isEqualTo("e717fd63");
        assertThat(response.isCacheHit()).isTrue();
        assertThat(response.getCacheKey()).isEqualTo("cache-key-1");
        assertThat(response.getSourceRunId()).isEqualTo("run_source_001");

        verify(repoRunRepository).save(any(RepoRun.class));
        verify(pipelineQueueService, never()).enqueue(any(RepoRun.class), eq(userId));
    }

    @Test
    void cache_hit이더라도_부분성공_결과면_재사용하지_않고_신규_분석을_실행한다() {
        Long userId = 1L;
        RepoRunCreateRequest request = request("https://github.com/apache/commons-cli", "master");
        User requester = user(userId);

        User sourceOwner = user(2L);
        RepoRun sourceRun = new RepoRun(
                "run_source_partial_001",
                sourceOwner,
                "https://github.com/apache/commons-cli",
                "apache",
                "commons-cli",
                "master",
                "e717fd63",
                "C:/data/ossdoc/run_source_partial_001"
        );
        sourceRun.markPartialSuccess();

        when(userRepository.findById(userId)).thenReturn(Optional.of(requester));
        when(githubClient.resolveCommitSha("apache", "commons-cli", "master")).thenReturn("e717fd63");
        when(analysisCacheLookupService.lookupReady(any(), eq("github://apache/commons-cli"), eq("e717fd63")))
                .thenReturn(AnalysisCacheLookupResult.hit("cache-key-1", "run_source_partial_001", "REDIS_HIT_DB_CONFIRMED"));
        when(repoRunRepository.findByRunIdAndOwner_Id("run_source_partial_001", userId))
                .thenReturn(Optional.empty());
        when(repoRunRepository.findById("run_source_partial_001"))
                .thenReturn(Optional.of(sourceRun));
        when(workspaceManager.workspaceRoot(any())).thenReturn(Path.of("C:/data/ossdoc/run_new_after_partial"));

        RepoRunCreateResponse response = repoRunService.createRun(request, userId);

        assertThat(response.getRunId()).startsWith("run_");
        assertThat(response.getRunId()).isNotEqualTo("run_source_partial_001");
        assertThat(response.isCacheHit()).isFalse();
        assertThat(response.getCacheKey()).isNotBlank();
        assertThat(response.getSourceRunId()).isNull();

        ArgumentCaptor<RepoRun> runCaptor = ArgumentCaptor.forClass(RepoRun.class);
        verify(repoRunRepository).save(runCaptor.capture());
        verify(pipelineQueueService).enqueue(runCaptor.getValue(), userId);
    }

    @Test
    void cache_miss이면_기존처럼_신규_run을_저장하고_파이프라인을_enqueue한다() {
        Long userId = 1L;
        RepoRunCreateRequest request = request("https://github.com/apache/commons-cli", "master");
        User owner = user(userId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(owner));
        when(githubClient.resolveCommitSha("apache", "commons-cli", "master")).thenReturn("e717fd63");
        when(analysisCacheLookupService.lookupReady(any(), eq("github://apache/commons-cli"), eq("e717fd63")))
                .thenReturn(AnalysisCacheLookupResult.miss("CACHE_MISS"));
        when(workspaceManager.workspaceRoot(any())).thenReturn(Path.of("C:/data/ossdoc/run_new_001"));

        RepoRunCreateResponse response = repoRunService.createRun(request, userId);

        assertThat(response.getRunId()).startsWith("run_");
        assertThat(response.getCommitSha()).isEqualTo("e717fd63");
        assertThat(response.getWorkspaceRoot()).isEqualTo(Path.of("C:/data/ossdoc/run_new_001").toString());
        assertThat(response.isCacheHit()).isFalse();
        assertThat(response.getCacheKey()).isNotBlank();
        assertThat(response.getSourceRunId()).isNull();

        ArgumentCaptor<RepoRun> runCaptor = ArgumentCaptor.forClass(RepoRun.class);
        verify(repoRunRepository).save(runCaptor.capture());
        verify(pipelineQueueService).enqueue(runCaptor.getValue(), userId);
    }

    private RepoRunCreateRequest request(String repoUrl, String ref) {
        RepoRunCreateRequest request = new RepoRunCreateRequest();
        ReflectionTestUtils.setField(request, "repoUrl", repoUrl);
        ReflectionTestUtils.setField(request, "ref", ref);
        return request;
    }

    private User user(Long userId) {
        return User.builder()
                .id(userId)
                .provider(AuthProvider.GOOGLE)
                .providerId("provider-1")
                .email("user@example.com")
                .name("tester")
                .role(UserRole.USER)
                .active(true)
                .build();
    }

    private RunPipelineJob successJob(RepoRun run, Long userId) {
        RunPipelineJob job = RunPipelineJob.create(run, userId);
        job.markSuccess();
        return job;
    }

}

package com.example.ossdoc.domain.run.service;

import com.example.ossdoc.domain.run.cache.model.AnalysisCacheLookupResult;
import com.example.ossdoc.domain.run.cache.service.AnalysisCacheLookupService;
import com.example.ossdoc.domain.run.dto.request.RepoRunCreateRequest;
import com.example.ossdoc.domain.run.dto.response.RepoRunCreateResponse;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.repository.RepoRunRepository;
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
                cacheProperties
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

        RepoRunCreateResponse response = repoRunService.createRun(request, userId);

        assertThat(response.getRunId()).isEqualTo("run_cached_001");
        assertThat(response.getCommitSha()).isEqualTo("e717fd63");
        assertThat(response.getWorkspaceRoot()).isEqualTo("C:/data/ossdoc/run_cached_001");

        // cache hit 경로에서는 신규 run 저장/큐 적재를 수행하지 않아야 합니다.
        verify(repoRunRepository, never()).save(any(RepoRun.class));
        verify(pipelineQueueService, never()).enqueue(any(RepoRun.class), eq(userId));
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
}

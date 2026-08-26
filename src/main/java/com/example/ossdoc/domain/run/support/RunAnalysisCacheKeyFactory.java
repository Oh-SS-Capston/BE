package com.example.ossdoc.domain.run.support;

import com.example.ossdoc.domain.run.exception.RunException;
import com.example.ossdoc.domain.run.exception.code.RunErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 분석 결과 재사용 여부를 판단하는 캐시 키를 생성합니다.
 * <p>
 * 설계 의도:
 * - repoUrl 표기 흔들림(.git, 대소문자, 마지막 slash)을 정규화해서 동일 저장소를 같은 키로 묶습니다.
 * - SHA + 버전 축(파이프라인/LLM/프롬프트/스키마/옵션)을 모두 포함해 캐시 오염을 방지합니다.
 */
@Component
public class RunAnalysisCacheKeyFactory {

    private static final Pattern TRAILING_SLASH = Pattern.compile("/+$");
    private static final Pattern GIT_SUFFIX = Pattern.compile("\\.git$");

    public String buildKey(RunAnalysisCacheKeySeed seed) {
        return sha256Hex(buildCanonicalPayload(seed));
    }

    /**
     * Repo URL 정규화 결과만 필요할 때 사용하는 유틸 메서드입니다.
     * <p>
     * W06에서 Redis/DB 조회 입력값(repoUrlNorm)을 만들 때 동일 규칙을 재사용하기 위해 제공합니다.
     */
    public String normalizeRepoUrlForCache(String repoUrl) {
        return normalizeRepoUrl(repoUrl);
    }

    /**
     * 키 계산의 원본 문자열을 만듭니다.
     * <p>
     * 이 문자열 포맷은 운영 중 디버깅 시 "왜 캐시가 갈렸는지" 추적하기 위한 기준이 됩니다.
     */
    public String buildCanonicalPayload(RunAnalysisCacheKeySeed seed) {
        return String.join("\n",
                "repo=" + normalizeRepoUrl(seed.getRepoUrl()),
                "sha=" + normalizeToken(seed.getCommitSha(), "sha:unknown"),
                "pipeline=" + normalizeToken(seed.getPipelineContractVersion(), "pipeline:v1"),
                "llm=" + normalizeToken(seed.getLlmProfileVersion(), "llm:v1"),
                "prompt=" + normalizeToken(seed.getPromptTemplateVersion(), "prompt:v1"),
                "schema=" + normalizeToken(seed.getOutputSchemaVersion(), "schema:v1"),
                "options=" + normalizeToken(seed.getRunOptionsSignature(), "options:default"),
                "provider=" + normalizeToken(seed.getLlmProvider(), "provider:default")
        );
    }

    /**
     * GitHub URL은 owner/repo만으로 정규화합니다.
     * parse 실패 시에도 캐시 키 계산이 중단되지 않도록 안전 폴백 문자열을 사용합니다.
     */
    private String normalizeRepoUrl(String repoUrl) {
        String fallback = normalizeToken(repoUrl, "repo:unknown");
        try {
            GithubRepoRef parsed = GithubUrlParser.parse(fallback, null);
            String owner = normalizeToken(parsed.getOwner(), "unknown-owner");
            String repo = normalizeToken(parsed.getRepo(), "unknown-repo");
            return "github://" + owner + "/" + repo;
        } catch (RunException ignored) {
            String lowered = fallback.toLowerCase(Locale.ROOT);
            String withoutSlash = TRAILING_SLASH.matcher(lowered).replaceAll("");
            return GIT_SUFFIX.matcher(withoutSlash).replaceAll("");
        }
    }

    /**
     * 빈 문자열/공백은 고정 fallback으로 치환해 키 충돌을 줄이고,
     * 대소문자 차이를 제거해 동일 의미 입력을 같은 토큰으로 맞춥니다.
     */
    private String normalizeToken(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            /*
             * Java 표준 런타임에서 SHA-256은 항상 제공됩니다.
             * 그래도 극단적인 환경 이상을 대비해 도메인 예외로 전환합니다.
             */
            throw new RunException(RunErrorCode.PIPELINE_EXECUTION_FAILED);
        }
    }
}

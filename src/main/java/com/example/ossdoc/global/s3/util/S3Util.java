package com.example.ossdoc.global.s3.util;

import com.example.ossdoc.global.s3.exception.S3Exception;
import com.example.ossdoc.global.s3.exception.code.S3ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;

/**
 * S3 업로드 / 삭제 헬퍼.
 *
 * <p>주요 사용처: ArtifactService — LLM이 생성한 JSON을 S3에 저장하고
 * 반환된 URL을 Artifact.path 컬럼에 기록한다.
 *
 * <p>S3 키 규칙:
 * <pre>
 *   artifacts/{runId}/{relativePath}
 *   예) artifacts/run_20260315_abc12345/llm/refined_rules.json
 * </pre>
 *
 * <p>예외: 모든 S3 오류는 {@link S3Exception}으로 변환되어
 * {@link com.example.ossdoc.global.apiPayload.exception.ExceptionAdvice}에서
 * 공통 응답 포맷으로 처리된다.
 */
@Slf4j
@Component
public class S3Util {

    private final S3Client s3Client;
    private final String   bucketName;

    @Value("${cloud.aws.region.static:ap-northeast-2}")
    private String region;

    public S3Util(S3Client s3Client,
                  @Qualifier("s3BucketName") String bucketName) {
        this.s3Client   = s3Client;
        this.bucketName = bucketName;
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * JSON 문자열을 S3에 업로드한다.
     *
     * @param key      S3 객체 키 (예: "artifacts/run_xxx/llm/refined_rules.json")
     * @param jsonBody 업로드할 JSON 문자열
     * @return         업로드된 객체의 Public HTTPS URL
     * @throws S3Exception S3_500_001 — 업로드 중 AWS SDK 오류 발생 시
     */
    public String uploadJson(String key, String jsonBody) {
        if (key == null || key.isBlank()) {
            throw new S3Exception(S3ErrorCode.INVALID_S3_KEY);
        }

        try {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/json")
                    .contentLength((long) bytes.length)
                    .build();

            PutObjectResponse response = s3Client.putObject(request, RequestBody.fromBytes(bytes));
            log.info("S3 업로드 완료 — key: {}, eTag: {}", key, response.eTag());

            return buildUrl(key);

        } catch (SdkException e) {
            log.error("S3 업로드 실패 — key: {}, 원인: {}", key, e.getMessage());
            throw new S3Exception(S3ErrorCode.UPLOAD_FAILED);
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * S3 URL 또는 키로 객체를 삭제한다.
     *
     * @param s3UrlOrKey 전체 S3 URL 또는 키 문자열 모두 허용
     * @throws S3Exception S3_500_002 — 삭제 중 AWS SDK 오류 발생 시
     */
    public void delete(String s3UrlOrKey) {
        String key = extractKey(s3UrlOrKey);
        if (key == null || key.isBlank()) {
            log.warn("S3 삭제 건너뜀 — 키를 추출할 수 없습니다. 입력값: {}", s3UrlOrKey);
            return;
        }

        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
            log.info("S3 삭제 완료 — key: {}", key);

        } catch (SdkException e) {
            log.error("S3 삭제 실패 — key: {}, 원인: {}", key, e.getMessage());
            throw new S3Exception(S3ErrorCode.DELETE_FAILED);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * S3 키로부터 HTTPS URL을 조립한다.
     * 형식: https://{bucket}.s3.{region}.amazonaws.com/{key}
     */
    public String buildUrl(String key) {
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, key);
    }

    /**
     * S3 URL에서 키를 추출한다. 이미 키 형태이면 그대로 반환한다.
     */
    public String extractKey(String s3UrlOrKey) {
        if (s3UrlOrKey == null || s3UrlOrKey.isBlank()) return null;

        String prefix = String.format("https://%s.s3.%s.amazonaws.com/", bucketName, region);
        if (s3UrlOrKey.startsWith(prefix)) {
            return s3UrlOrKey.substring(prefix.length());
        }

        return s3UrlOrKey;
    }
}
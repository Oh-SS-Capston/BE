package com.example.ossdoc.domain.extraction.service.support;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * S3에서 build_manifest.json을 읽어 BuildManifest로 역직렬화.
 *
 * <p>S3 키 규칙: {@code artifacts/{runId}/build_manifest.json}
 */
@Slf4j
@Component
public class BuildManifestS3Loader {

    private static final String KEY_PATTERN = "artifacts/%s/build_manifest.json";

    private final S3Client s3Client;
    private final String bucketName;
    private final ObjectMapper objectMapper;

    public BuildManifestS3Loader(S3Client s3Client,
                                 @Qualifier("s3BucketName") String bucketName,
                                 ObjectMapper objectMapper) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.objectMapper = objectMapper;
    }

    /**
     * S3에서 build_manifest.json을 읽어 BuildManifest로 반환.
     * 파일이 없거나 역직렬화 실패 시 null 반환 (warning은 호출부에서 추가).
     */
    public BuildManifest load(String runId) {
        String key = String.format(KEY_PATTERN, runId);
        try {
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build()
            );
            return objectMapper.readValue(response, BuildManifest.class);
        } catch (NoSuchKeyException e) {
            log.warn("build_manifest not found in S3 — key: {}", key);
            return null;
        } catch (Exception e) {
            log.warn("build_manifest S3 로드 실패 — key: {}, 원인: {}", key, e.getMessage());
            return null;
        }
    }
}

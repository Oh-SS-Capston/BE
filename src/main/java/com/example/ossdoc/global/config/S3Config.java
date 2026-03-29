package com.example.ossdoc.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Slf4j
@Configuration
public class S3Config {

    @Value("${cloud.aws.credentials.access-key:#{null}}")
    private String accessKeyId;

    @Value("${cloud.aws.credentials.secret-key:#{null}}")
    private String secretAccessKey;

    @Value("${cloud.aws.region.static:ap-northeast-2}")
    private String region;

    @Value("${cloud.aws.s3.bucket:ossdoc-artifact-storage}")
    private String bucketName;

    @Bean
    public S3Client s3Client() {
        Region awsRegion = Region.of(region);

        // 우선순위: yml → 환경변수 → IAM Role(DefaultCredentialsProvider)
        String keyId     = firstNonBlank(accessKeyId,     System.getenv("AWS_ACCESS_KEY_ID"));
        String secretKey = firstNonBlank(secretAccessKey, System.getenv("AWS_SECRET_ACCESS_KEY"));

        AwsCredentialsProvider credentialsProvider;
        if (keyId != null && secretKey != null) {
            credentialsProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(keyId, secretKey));
        } else {
            credentialsProvider = DefaultCredentialsProvider.create();
        }

        log.info("S3Client initialized — region: {}, bucket: {}", awsRegion, bucketName);

        return S3Client.builder()
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .build();
    }

    /** S3 버킷 이름을 Bean으로 노출 — 다른 컴포넌트에서 @Qualifier 없이 주입 가능 */
    @Bean(name = "s3BucketName")
    public String s3BucketName() {
        return bucketName;
    }

    private String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return null;
    }
}
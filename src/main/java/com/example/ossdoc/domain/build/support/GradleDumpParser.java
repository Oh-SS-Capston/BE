package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.dto.json.BuildFailure;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.build.exception.code.BuildErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 역할:
 * Gradle init script(ossdocDump) 출력 문자열을 모듈 매니페스트로 변환한다.
 *
 * 책임:
 * 1) OSS_DOC_DUMP JSON 라인 추출
 * 2) source/test/resource/classes/classpath 경로 정규화
 * 3) 모듈 상태(OK/PARTIAL/FAILED) 판정
 * 4) 파싱 실패를 BuildFailure로 누적
 */
@Component
@RequiredArgsConstructor
public class GradleDumpParser {

    private final ObjectMapper objectMapper;
    private final BuildPathNormalizer buildPathNormalizer;

    /**
     * 역할:
     * dump 출력 전체를 파싱해 BuildModuleManifest 목록으로 반환한다.
     *
     * 책임:
     * 유효한 dump 라인을 모두 읽어 모듈 정보를 생성하고, 예외는 failures에 기록한다.
     */
    public List<BuildModuleManifest> parse(Path repoRoot, String output, List<BuildFailure> failures) {
        Pattern p = Pattern.compile("^OSS_DOC_DUMP=(\\{.*\\})$", Pattern.MULTILINE);
        Matcher m = p.matcher(output);

        List<BuildModuleManifest> modules = new ArrayList<>();
        while (m.find()) {
            try {
                Map<String, Object> map = objectMapper.readValue(m.group(1), Map.class);

                List<String> sourceRoots = readPathList(map, "sourceRoots", repoRoot);
                List<String> testRoots = readPathList(map, "testRoots", repoRoot);
                List<String> resourceRoots = readPathList(map, "resourceRoots", repoRoot);
                List<String> classesDirs = readPathList(map, "classesDirs", repoRoot);
                List<String> compileClasspath = readPathList(map, "compileClasspath", repoRoot);
                List<String> runtimeClasspath = readPathList(map, "runtimeClasspath", repoRoot);

                String status;
                if (!classesDirs.isEmpty()) {
                    status = "OK";
                } else if (!sourceRoots.isEmpty() || !testRoots.isEmpty() || !resourceRoots.isEmpty()) {
                    status = "PARTIAL";
                } else {
                    status = "FAILED";
                }

                modules.add(BuildModuleManifest.builder()
                        .moduleId((String) map.getOrDefault("projectPath", ""))
                        .name((String) map.getOrDefault("name", ""))
                        .sourceRoots(sourceRoots)
                        .testRoots(testRoots)
                        .resourceRoots(resourceRoots)
                        .classesDirs(classesDirs)
                        .compileClasspath(compileClasspath)
                        .runtimeClasspath(runtimeClasspath)
                        .status(status)
                        .build());

            } catch (Exception e) {
                failures.add(BuildFailure.builder()
                        .code(BuildErrorCode.GRADLE_DUMP_FAILED.getCode())
                        .message("Failed to parse OSS_DOC_DUMP - " + e.getMessage())
                        .build());
            }
        }
        return modules;
    }

    private List<String> readPathList(Map<String, Object> map, String key, Path repoRoot) {
        Object rawValue = map.get(key);
        if (!(rawValue instanceof List<?> values)) {
            return List.of();
        }

        List<String> pathValues = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String path && !path.isBlank()) {
                pathValues.add(path);
            }
        }

        return buildPathNormalizer.toAbsolutePaths(repoRoot, pathValues);
    }
}

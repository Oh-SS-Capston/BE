package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntry;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntryId;
import com.example.ossdoc.domain.publicapi.repository.PublicApiEntryRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/*
 * 역할:
 * run 기준 Symbol 정보를 바탕으로 public_api_entry를 일관되게 생성합니다.
 *
 * 주의:
 * public_api_entry는 이후 cluster 단계의 선행 입력으로 사용됩니다.
 * 따라서 이 단계에서는 "실제 운영 코드일 가능성이 있는 타입"을 너무 공격적으로 제거하면 안 됩니다.
 *
 * 예:
 * - org.springframework.samples.petclinic
 *   → 패키지명에 samples가 들어가지만, PetClinic 프로젝트의 실제 메인 로직입니다.
 *
 * 그래서 이 서비스에서는
 * 1. 명확한 테스트 코드만 제외하고
 * 2. sample/example/demo 같은 단어만으로는 메인 로직을 제거하지 않습니다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PublicApiEntrySyncService {

    /*
     * 명확한 테스트 소스 루트만 제외합니다.
     *
     * sample/example/demo 경로는 제외하지 않습니다.
     * 이유:
     * - 어떤 저장소에서는 별도 예제 폴더일 수 있지만
     * - 어떤 저장소에서는 실제 프로젝트 본체일 수 있습니다.
     * - public_api_entry는 cluster의 입력이므로, 여기서 너무 일찍 제거하면
     *   실제 서비스 로직 전체가 군집화 대상에서 사라질 수 있습니다.
     */
    private static final List<String> NON_PRODUCTION_PATH_MARKERS = List.of(
            "src/test/",
            "src/it/",
            "src/integrationtest/",
            "src/integration-test/"
    );

    /*
     * 패키지명만 보고 제외해도 비교적 안전한 테스트/벤치마크 계열 토큰만 둡니다.
     *
     * 제거한 토큰:
     * - example, examples
     * - sample, samples
     * - demo, demos
     *
     * 이유:
     * - org.springframework.samples.petclinic 처럼
     *   실제 메인 패키지에 samples가 포함되는 프로젝트가 존재합니다.
     * - 이런 단어는 "비운영 코드"의 강한 증거가 아닙니다.
     */
    private static final Set<String> NON_PRODUCTION_PACKAGE_TOKENS = Set.of(
            "test",
            "tests",
            "it",
            "benchmark",
            "benchmarks"
    );

    private final PublicApiEntryRepository publicApiEntryRepository;
    private final SymbolRepository symbolRepository;

    /**
     * 역할:
     * run의 TYPE 심볼을 읽어 공개 API 엔트리를 동기화하고,
     * 생성된 public API TYPE의 symbol id 집합을 반환합니다.
     */
    public Set<String> ensureTypeEntries(RepoRun run) {
        List<SymbolEntity> typeSymbols =
                symbolRepository.findAllByRun_RunIdAndSymbolKind(
                        run.getRunId(),
                        SymbolKind.TYPE
                );

        return ensureTypeEntries(run, typeSymbols);
    }

    /**
     * 역할:
     * 전달받은 TYPE 목록을 기준으로 공개 API 엔트리를 전량 재생성합니다.
     *
     * 기존 엔트리를 지운 뒤 최신 Symbol 상태 기준으로 다시 만드는 이유:
     * - 같은 run에서 재실행하더라도 중복 엔트리를 방지
     * - symbol access / 필터 정책 변경 시 결과를 일관되게 재동기화
     */
    public Set<String> ensureTypeEntries(RepoRun run, List<SymbolEntity> typeSymbols) {
        List<SymbolEntity> visibleTypes = collectVisibleTypes(typeSymbols);

        publicApiEntryRepository.deleteByRun_RunId(run.getRunId());

        if (visibleTypes.isEmpty()) {
            return Set.of();
        }

        List<PublicApiEntry> entries = new ArrayList<>(visibleTypes.size());
        LinkedHashSet<String> symbolIds = new LinkedHashSet<>();

        for (SymbolEntity symbol : visibleTypes) {
            PublicApiEntry entry = new PublicApiEntry(
                    new PublicApiEntryId(run.getRunId(), symbol.getSymbolId()),
                    run,
                    symbol,
                    SymbolKind.TYPE,
                    resolveExposure(symbol.getAccess()),
                    buildReason(symbol)
            );

            entries.add(entry);
            symbolIds.add(symbol.getSymbolId());
        }

        publicApiEntryRepository.saveAll(entries);

        return symbolIds;
    }

    /**
     * 역할:
     * 공개 대상 접근 제어(public/protected) + 명확한 비운영 코드 여부를 기준으로
     * TYPE을 필터링합니다.
     *
     * 여기서 제외하는 것은 "명확히 테스트/벤치마크 코드라고 판단 가능한 것"으로 제한합니다.
     */
    private List<SymbolEntity> collectVisibleTypes(List<SymbolEntity> typeSymbols) {
        if (typeSymbols == null || typeSymbols.isEmpty()) {
            return List.of();
        }

        List<SymbolEntity> visible = new ArrayList<>();

        for (SymbolEntity type : typeSymbols) {
            if (type == null || type.getSymbolKind() != SymbolKind.TYPE) {
                continue;
            }

            if (!isPublicOrProtected(type.getAccess())) {
                continue;
            }

            if (isClearlyNonProductionType(type)) {
                continue;
            }

            visible.add(type);
        }

        return List.copyOf(visible);
    }

    /**
     * 역할:
     * 명확하게 테스트/벤치마크 코드로 볼 수 있는 타입만 제외합니다.
     *
     * 제외 기준:
     * 1. source path가 test 계열 소스 루트에 속함
     * 2. package token이 test/tests/it/benchmark/benchmarks
     * 3. simple name이 Test/Tests/TestCase로 끝남
     *
     * 제외하지 않는 것:
     * - sample/samples
     * - example/examples
     * - demo/demos
     *
     * 이유:
     * 위 단어들은 실제 메인 프로젝트 이름이나 패키지명으로도 자주 쓰이므로
     * 단어만 보고 제거하면 실제 로직을 누락할 위험이 큽니다.
     */
    private boolean isClearlyNonProductionType(SymbolEntity type) {
        String sourcePath = normalizePath(
                type.getSourceFile() == null
                        ? null
                        : type.getSourceFile().getPath()
        );

        if (sourcePath != null) {
            for (String marker : NON_PRODUCTION_PATH_MARKERS) {
                if (sourcePath.contains(marker)) {
                    return true;
                }
            }
        }

        String packageName = extractPackageName(type.getQualifiedName());

        if (packageName != null) {
            String[] tokens = packageName
                    .toLowerCase(Locale.ROOT)
                    .split("\\.");

            for (String token : tokens) {
                if (NON_PRODUCTION_PACKAGE_TOKENS.contains(token)) {
                    return true;
                }
            }
        }

        String simpleName = trimToNull(type.getSimpleName());

        if (simpleName != null) {
            String lower = simpleName.toLowerCase(Locale.ROOT);

            if (
                    lower.endsWith("test")
                            || lower.endsWith("tests")
                            || lower.endsWith("testcase")
            ) {
                return true;
            }
        }

        return false;
    }

    /**
     * 역할:
     * access 값을 public_api_entry.exposure 문자열로 변환합니다.
     */
    private String resolveExposure(AccessLevel accessLevel) {
        if (accessLevel == null) {
            return "UNKNOWN";
        }

        return accessLevel.name();
    }

    /**
     * 역할:
     * 공개 API로 분류한 근거를 JSON으로 기록합니다.
     */
    private ObjectNode buildReason(SymbolEntity symbol) {
        ObjectNode reason = JsonNodeFactory.instance.objectNode();

        reason.put("source", "graphstore_symbol_access");
        reason.put(
                "symbolKind",
                symbol.getSymbolKind() == null
                        ? "UNKNOWN"
                        : symbol.getSymbolKind().name()
        );
        reason.put(
                "access",
                symbol.getAccess() == null
                        ? "UNKNOWN"
                        : symbol.getAccess().name()
        );
        reason.put(
                "rule",
                "TYPE and access in [PUBLIC, PROTECTED] + clear test/benchmark exclusion"
        );

        return reason;
    }

    /**
     * 역할:
     * qualified name에서 패키지명을 추출합니다.
     */
    private String extractPackageName(String qualifiedName) {
        String normalized = trimToNull(qualifiedName);

        if (normalized == null) {
            return null;
        }

        int idx = normalized.lastIndexOf('.');

        if (idx <= 0) {
            return null;
        }

        return normalized.substring(0, idx);
    }

    /**
     * 역할:
     * 운영체제 경로 구분자 차이를 없애기 위해 슬래시/소문자로 정규화합니다.
     */
    private String normalizePath(String rawPath) {
        String path = trimToNull(rawPath);

        if (path == null) {
            return null;
        }

        return path
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 역할:
     * 공백 문자열을 null로 정규화합니다.
     */
    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }

        String trimmed = text.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 역할:
     * 공개 API 후보 접근 제어(public/protected) 여부를 판별합니다.
     */
    private boolean isPublicOrProtected(AccessLevel accessLevel) {
        return accessLevel == AccessLevel.PUBLIC
                || accessLevel == AccessLevel.PROTECTED;
    }
}
// 역할: run 기준 Symbol 정보를 바탕으로 public_api_entry를 일관되게 생성한다.
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

@Service
@RequiredArgsConstructor
@Transactional
public class PublicApiEntrySyncService {
    private static final List<String> NON_PRODUCTION_PATH_MARKERS = List.of(
            "src/test/",
            "src/it/",
            "src/integrationtest/",
            "src/integration-test/",
            "/example/",
            "/examples/",
            "/sample/",
            "/samples/",
            "/demo/",
            "/demos/"
    );

    private static final Set<String> NON_PRODUCTION_PACKAGE_TOKENS = Set.of(
            "test",
            "tests",
            "it",
            "example",
            "examples",
            "sample",
            "samples",
            "demo",
            "demos",
            "benchmark",
            "benchmarks"
    );

    private final PublicApiEntryRepository publicApiEntryRepository;
    private final SymbolRepository symbolRepository;

    /**
     * 역할: run의 TYPE 심볼을 읽어 공개 API 엔트리를 동기화하고 symbol id 집합을 반환한다.
     */
    public Set<String> ensureTypeEntries(RepoRun run) {
        List<SymbolEntity> typeSymbols = symbolRepository.findAllByRun_RunIdAndSymbolKind(run.getRunId(), SymbolKind.TYPE);
        return ensureTypeEntries(run, typeSymbols);
    }

    /**
     * 역할: 전달받은 TYPE 목록을 기준으로 공개 API 엔트리를 전량 재생성한다.
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
     * 역할: 공개 대상 접근제어(public/protected) + 운영 코드 여부를 기준으로 TYPE을 필터링한다.
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
            if (isNonProductionType(type)) {
                continue;
            }
            visible.add(type);
        }
        return List.copyOf(visible);
    }

    /**
     * 역할: 테스트/예제 성격 타입을 public API 후보에서 제외한다.
     */
    private boolean isNonProductionType(SymbolEntity type) {
        String sourcePath = normalizePath(type.getSourceFile() == null ? null : type.getSourceFile().getPath());
        if (sourcePath != null) {
            for (String marker : NON_PRODUCTION_PATH_MARKERS) {
                if (sourcePath.contains(marker)) {
                    return true;
                }
            }
        }

        String packageName = extractPackageName(type.getQualifiedName());
        if (packageName != null) {
            String[] tokens = packageName.toLowerCase(Locale.ROOT).split("\\.");
            for (String token : tokens) {
                if (NON_PRODUCTION_PACKAGE_TOKENS.contains(token)) {
                    return true;
                }
            }
        }

        String qualifiedName = trimToNull(type.getQualifiedName());
        if (qualifiedName != null) {
            for (String segment : qualifiedName.split("\\.")) {
                String lower = segment.toLowerCase(Locale.ROOT);
                if (lower.endsWith("test") || lower.endsWith("tests") || lower.endsWith("testcase")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 역할: access 값을 public_api_entry.exposure 문자열로 변환한다.
     */
    private String resolveExposure(AccessLevel accessLevel) {
        if (accessLevel == null) {
            return "UNKNOWN";
        }
        return accessLevel.name();
    }

    /**
     * 역할: 공개 API로 분류한 근거를 JSON으로 기록한다.
     */
    private ObjectNode buildReason(SymbolEntity symbol) {
        ObjectNode reason = JsonNodeFactory.instance.objectNode();
        reason.put("source", "graphstore_symbol_access");
        reason.put("symbolKind", symbol.getSymbolKind() == null ? "UNKNOWN" : symbol.getSymbolKind().name());
        reason.put("access", symbol.getAccess() == null ? "UNKNOWN" : symbol.getAccess().name());
        reason.put("rule", "TYPE and access in [PUBLIC, PROTECTED] + non-production exclusion");
        return reason;
    }

    /**
     * 역할: qualified name에서 패키지명을 추출한다.
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
     * 역할: 운영체제 경로 구분자 차이를 없애기 위해 슬래시/소문자로 정규화한다.
     */
    private String normalizePath(String rawPath) {
        String path = trimToNull(rawPath);
        if (path == null) {
            return null;
        }
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /**
     * 역할: 공백 문자열을 null로 정규화한다.
     */
    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 역할: 공개 API 후보 접근 제어(public/protected) 여부를 판별한다.
     */
    private boolean isPublicOrProtected(AccessLevel accessLevel) {
        return accessLevel == AccessLevel.PUBLIC || accessLevel == AccessLevel.PROTECTED;
    }
}

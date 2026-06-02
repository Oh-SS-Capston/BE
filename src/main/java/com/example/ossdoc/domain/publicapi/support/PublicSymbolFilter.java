package com.example.ossdoc.domain.publicapi.support;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/*
 * public_api_entry 저장 기준과 동일한 필터의 단일 소스(single source of truth).
 *
 * PublicApiEntrySyncService / ExtensionPointDetectService / EntryPointDetectService
 * 모두 이 클래스를 통해 "공개 API TYPE인가"를 판정한다.
 * 필터 정책을 바꿀 때는 이 파일 한 곳만 수정하면 된다.
 *
 * 제외 정책 설계 원칙:
 * - 명확히 테스트/벤치마크 코드로 볼 수 있는 타입만 제외한다.
 * - sample/example/demo 경로·패키지는 제외하지 않는다.
 *   이유: org.springframework.samples.petclinic 처럼 실제 메인 로직에 이 단어가
 *   포함되는 프로젝트가 존재한다. public_api_entry는 cluster의 입력이므로
 *   여기서 너무 일찍 제거하면 실제 서비스 로직 전체가 군집화 대상에서 사라질 수 있다.
 */
public final class PublicSymbolFilter {

    // PublicApiEntrySyncService에서 이동 — 정책 변경 시 이 상수만 수정한다
    public static final List<String> NON_PRODUCTION_PATH_MARKERS = List.of(
            "src/test/",
            "src/it/",
            "src/integrationtest/",
            "src/integration-test/"
    );

    /*
     * 패키지명만 보고 제외해도 비교적 안전한 테스트/벤치마크 계열 토큰.
     *
     * 제거한 토큰: example, examples, sample, samples, demo, demos
     * 이유: 실제 메인 패키지에 포함되는 경우가 있어 "비운영 코드"의 강한 증거가 아니다.
     */
    public static final Set<String> NON_PRODUCTION_PACKAGE_TOKENS = Set.of(
            "test",
            "tests",
            "it",
            "benchmark",
            "benchmarks"
    );

    private PublicSymbolFilter() {}

    /**
     * public_api_entry 저장 기준과 동일: public 또는 protected TYPE이고, 명확한 비운영 코드가 아닌 경우 true.
     *
     * ⚠️ 필드·마커·토큰·대소문자 처리를 임의로 단순화하지 말 것.
     * 이 판정이 sync의 INSERT와 detect의 in-memory 필터를 동기화하는 기준점이다.
     */
    public static boolean isPublicApiType(SymbolEntity symbol) {
        if (symbol == null || symbol.getSymbolKind() != SymbolKind.TYPE) return false;
        AccessLevel access = symbol.getAccess();
        if (access != AccessLevel.PUBLIC && access != AccessLevel.PROTECTED) return false;
        return !isClearlyNonProductionType(symbol);
    }

    /*
     * 제외 기준:
     * 1. source path (sourceFile.getPath())가 테스트 계열 소스 루트에 속함
     *    — getSourceRoot()가 아닌 getSourceFile().getPath()를 사용한다 (sync와 동일).
     * 2. 패키지를 '.'으로 분리한 각 세그먼트가 NON_PRODUCTION_PACKAGE_TOKENS에 해당
     *    — qn.contains() 방식이 아닌 세그먼트 단위 매칭 (sync와 동일).
     * 3. simpleName을 소문자화한 결과가 "test"/"tests"/"testcase"로 끝남
     *    — 대소문자 구분 없이 소문자화 후 비교 (sync와 동일).
     */
    private static boolean isClearlyNonProductionType(SymbolEntity symbol) {
        String sourcePath = symbol.getSourceFile() == null
                ? null
                : symbol.getSourceFile().getPath();

        if (sourcePath != null) {
            String norm = sourcePath.replace('\\', '/').toLowerCase(Locale.ROOT);
            for (String marker : NON_PRODUCTION_PATH_MARKERS) {
                if (norm.contains(marker)) return true;
            }
        }

        String pkg = extractPackageName(symbol.getQualifiedName());
        if (pkg != null) {
            for (String token : pkg.toLowerCase(Locale.ROOT).split("\\.")) {
                if (NON_PRODUCTION_PACKAGE_TOKENS.contains(token)) return true;
            }
        }

        String simpleName = symbol.getSimpleName();
        if (simpleName != null) {
            String lower = simpleName.toLowerCase(Locale.ROOT);
            if (lower.endsWith("test") || lower.endsWith("tests") || lower.endsWith("testcase")) {
                return true;
            }
        }

        return false;
    }

    private static String extractPackageName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) return null;
        int idx = qualifiedName.lastIndexOf('.');
        return idx <= 0 ? null : qualifiedName.substring(0, idx);
    }
}

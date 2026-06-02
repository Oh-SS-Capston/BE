package com.example.ossdoc.global.llm.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * LLM 입력 조립 시 공통으로 사용하는 문자열/경로/식별자 정적 유틸 클래스.
 */
public final class LlmInputAssemblerSupport {

    private LlmInputAssemblerSupport() {
    }

    private static final int MAX_SNIPPET_LENGTH = 480;
    private static final String MAIN_JAVA_MARKER = "/src/main/java/";
    private static final String MAIN_KOTLIN_MARKER = "/src/main/kotlin/";
    private static final String TEST_MARKER = "/src/test/";
    private static final String TARGET_MARKER = "/target/";

    /**
     * 심볼/메서드 식별자 문자열을 정규화하는 역할.
     */
    public static String sanitizeQualifiedName(String qn) {
        String value = safeText(qn);
        if (value.startsWith("type:") || value.startsWith("method:")) {
            int idx = value.indexOf(':');
            if (idx >= 0 && idx + 1 < value.length()) {
                return value.substring(idx + 1);
            }
        }
        if (value.startsWith("ctor:")) {
            return value.substring("ctor:".length());
        }
        return value;
    }

    public static boolean isMethodQualifiedName(String qn) {
        String value = safeText(qn);
        return value.contains("#") || value.contains("(") || isMethodFqnShape(value);
    }

    public static boolean isMethodFqnShape(String fqn) {
        String value = safeText(fqn);
        if (value.contains("#") || value.contains("(")) {
            return true;
        }
        int idx = value.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= value.length()) {
            return false;
        }
        String tail = value.substring(idx + 1);
        return !tail.isBlank() && Character.isLowerCase(tail.charAt(0));
    }

    public static String extractOwnerFqn(String methodQualified) {
        String value = safeText(methodQualified);
        if (value.contains("#")) {
            return value.substring(0, value.indexOf('#'));
        }
        int paramsIdx = value.indexOf('(');
        if (paramsIdx > 0) {
            value = value.substring(0, paramsIdx);
        }
        int idx = value.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return value.substring(0, idx);
    }

    public static String extractMethodName(String methodQualified) {
        String value = safeText(methodQualified);
        if (value.contains("#")) {
            String tail = value.substring(value.indexOf('#') + 1);
            int p = tail.indexOf('(');
            return p >= 0 ? tail.substring(0, p) : tail;
        }
        int idx = value.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= value.length()) {
            return value;
        }
        String tail = value.substring(idx + 1);
        int p = tail.indexOf('(');
        return p >= 0 ? tail.substring(0, p) : tail;
    }

    public static String resolveTypeQualifiedName(JsonNode rank) {
        String symbolId = safeText(rank.path("symbolId").asText(""));
        String qualifiedName = sanitizeQualifiedName(rank.path("qualifiedName").asText(""));
        if (qualifiedName.isBlank() && symbolId.startsWith("type:")) {
            qualifiedName = sanitizeQualifiedName(symbolId);
        }
        if (qualifiedName.isBlank()) {
            return "";
        }
        if (symbolId.startsWith("method:") || symbolId.startsWith("ctor:")) {
            return "";
        }
        if (isMethodQualifiedName(qualifiedName)) {
            return "";
        }
        String simpleName = extractSimpleName(qualifiedName);
        if (simpleName.isBlank() || !Character.isUpperCase(simpleName.charAt(0))) {
            return "";
        }
        return qualifiedName;
    }

    public static String resolveMethodQualifiedName(JsonNode rank) {
        String qualifiedName = sanitizeQualifiedName(rank.path("qualifiedName").asText(""));
        String symbolId = safeText(rank.path("symbolId").asText(""));
        if (isMethodQualifiedName(qualifiedName)) {
            return qualifiedName;
        }
        String fromSymbolId = methodQualifiedNameFromSymbolId(symbolId);
        if (!fromSymbolId.isBlank()) {
            return fromSymbolId;
        }
        return "";
    }

    public static String methodQualifiedNameFromSymbolId(String symbolId) {
        String value = safeText(symbolId);
        if (value.startsWith("method:")) {
            return value.substring("method:".length());
        }
        if (value.startsWith("ctor:")) {
            return value.substring("ctor:".length());
        }
        return "";
    }

    /**
     * 분석 경로를 사용자 소스 경로로 정규화하는 역할.
     */
    public static boolean isUserFacingSourcePath(String filePath) {
        String normalized = normalizePath(toUserFacingSourcePath(filePath));
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (normalized.contains(TEST_MARKER) || normalized.contains(TARGET_MARKER)) {
            return false;
        }
        // leading slash 없이 시작하는 상대 경로(e.g. "src/main/java/...")도 인식한다.
        String withSlash = normalized.startsWith("/") ? normalized : "/" + normalized;
        return withSlash.contains(MAIN_JAVA_MARKER) || withSlash.contains(MAIN_KOTLIN_MARKER);
    }

    /**
     * 바이트코드 경로를 사용자 소스 경로로 정규화한다.
     */
    public static String toUserFacingSourcePath(String rawPath) {
        String normalized = safeText(rawPath).replace('\\', '/');
        if (normalized.isBlank()) {
            return "";
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        String fromMavenTarget = rewriteCompiledPath(
                normalized,
                lower,
                "/target/classes/",
                "/src/main/java/",
                ".java"
        );
        if (!fromMavenTarget.isBlank()) {
            return fromMavenTarget;
        }

        String fromGradleJava = rewriteCompiledPath(
                normalized,
                lower,
                "/build/classes/java/main/",
                "/src/main/java/",
                ".java"
        );
        if (!fromGradleJava.isBlank()) {
            return fromGradleJava;
        }

        String fromGradleKotlin = rewriteCompiledPath(
                normalized,
                lower,
                "/build/classes/kotlin/main/",
                "/src/main/kotlin/",
                ".kt"
        );
        if (!fromGradleKotlin.isBlank()) {
            return fromGradleKotlin;
        }

        return normalized;
    }

    private static String rewriteCompiledPath(
            String originalPath,
            String lowerPath,
            String marker,
            String sourceRoot,
            String sourceExtension
    ) {
        int markerIndex = lowerPath.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }

        String prefix = originalPath.substring(0, markerIndex);
        String suffix = originalPath.substring(markerIndex + marker.length());
        if (suffix.endsWith(".class")) {
            String classPath = suffix.substring(0, suffix.length() - ".class".length());
            int nestedTypeIndex = classPath.indexOf('$');
            if (nestedTypeIndex >= 0) {
                classPath = classPath.substring(0, nestedTypeIndex);
            }
            suffix = classPath + sourceExtension;
        }
        return prefix + sourceRoot + suffix;
    }

    public static String normalizePath(String rawPath) {
        String path = safeText(rawPath);
        if (path.isBlank()) {
            return null;
        }
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    public static String toApiFqn(String ownerFqn, String methodName, boolean constructor) {
        if (ownerFqn.isBlank()) {
            return methodName;
        }
        return constructor ? ownerFqn + ".<init>" : ownerFqn + "." + methodName;
    }

    public static String extractPackageName(String fqn) {
        String value = safeText(fqn);
        int idx = value.lastIndexOf('.');
        if (idx <= 0) {
            return "";
        }
        return value.substring(0, idx);
    }

    public static String extractSimpleName(String fqn) {
        String value = safeText(fqn);
        int idx = value.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= value.length()) {
            return value;
        }
        return value.substring(idx + 1);
    }

    public static String extractDirectoryPath(String filePath) {
        String normalized = safeText(filePath).replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        if (idx <= 0) {
            return ".";
        }
        return normalized.substring(0, idx);
    }

    /**
     * 클래스/메서드 이름 패턴을 사용자 가이드 문장으로 변환하는 역할.
     */
    public static String inferClassRole(String className, String fqn) {
        String lower = (className + " " + fqn).toLowerCase(Locale.ROOT);
        if (lower.contains("parser")) {
            return "입력 인자를 해석해 결과를 생성하는 핵심 파서 클래스";
        }
        if (lower.contains("commandline")) {
            return "파싱 결과를 조회하고 옵션 값을 다루는 결과 객체 클래스";
        }
        if (lower.contains("option")) {
            return "옵션 규격과 검증 규칙을 정의하는 설정 클래스";
        }
        if (lower.contains("help") || lower.contains("formatter")) {
            return "사용법과 오류 안내를 출력하는 도움말 클래스";
        }
        return "공개 API 사용 흐름에서 핵심 동작을 담당하는 클래스";
    }

    /**
     * 클래스 이름 패턴으로 언제 사용하는지 한 줄 가이드를 만든다.
     */
    public static String inferClassUsage(String className, String fqn) {
        String lower = (className + " " + fqn).toLowerCase(Locale.ROOT);
        if (lower.contains("parser")) {
            return "옵션 정의 후 실제 입력(args)을 파싱할 때 사용";
        }
        if (lower.contains("option")) {
            return "애플리케이션 시작 시 옵션 규격을 정의할 때 사용";
        }
        if (lower.contains("help")) {
            return "오류 처리나 --help 출력 시 사용";
        }
        return "핵심 API 호출 흐름에서 기능을 조합할 때 사용";
    }

    public static int methodHeuristicBonus(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        int bonus = 1;
        if (lower.contains("parse") || lower.contains("build")) {
            bonus += 4;
        }
        if (lower.contains("add") || lower.contains("required")) {
            bonus += 3;
        }
        if (lower.contains("get") || lower.contains("has")) {
            bonus += 2;
        }
        if (lower.contains("help") || lower.contains("print")) {
            bonus += 2;
        }
        return bonus;
    }

    /**
     * 메서드 이름 패턴 기반으로 사용자 설명형 usage 문장을 만든다.
     */
    public static String inferMethodUsage(String methodName, String ownerClass, String signatureHint) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.contains("parse")) {
            return "입력 인자를 해석해 실행에 사용할 결과 객체를 만들 때 호출합니다.";
        }
        if (lower.contains("add") || lower.contains("required") || lower.contains("builder")) {
            return "실행 전에 옵션/필수값을 설정하거나 구성할 때 호출합니다.";
        }
        if (lower.contains("get") || lower.contains("has")) {
            return "실행 결과에서 값 존재 여부를 확인하거나 값을 읽을 때 호출합니다.";
        }
        if (lower.contains("help") || lower.contains("print")) {
            return "사용법 또는 오류 안내를 출력할 때 호출합니다.";
        }
        if (!signatureHint.isBlank()) {
            return ownerClass + "의 핵심 기능을 연결할 때 호출합니다. 시그니처: "
                    + normalizeSummarySeed(shortenText(signatureHint, 80));
        }
        return ownerClass + "의 핵심 기능을 연결할 때 호출합니다.";
    }

    public static String inferScenarioHint(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (lower.contains("parse")) {
            return "SCN-파싱";
        }
        if (lower.contains("help") || lower.contains("print")) {
            return "SCN-도움말";
        }
        if (lower.contains("add") || lower.contains("required") || lower.contains("builder")) {
            return "SCN-옵션정의";
        }
        return "SCN-기본사용";
    }

    public static String normalizeClassification(String kind) {
        String lower = safeText(kind).toLowerCase(Locale.ROOT);
        if (lower.contains("null") || lower.contains("guard") || lower.contains("require")) {
            return "defensive";
        }
        return "domain";
    }

    public static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    public static String firstText(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public static Integer firstNonNullInt(Integer... values) {
        if (values == null) {
            return null;
        }
        for (Integer value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static int confidenceToImportanceBoost(String confidence) {
        String normalized = safeText(confidence).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "HIGH" -> 4;
            case "MED", "MEDIUM" -> 2;
            default -> 0;
        };
    }

    public static void putIfText(ObjectNode node, String key, String value) {
        if (node == null || key == null || value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isBlank()) {
            node.put(key, trimmed);
        }
    }

    public static Integer asNullableInt(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.canConvertToInt() ? node.asInt() : null;
    }

    public static String trimSnippet(String snippet) {
        if (snippet == null) {
            return null;
        }
        String normalized = snippet.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return null;
        }
        return shortenText(normalized, MAX_SNIPPET_LENGTH);
    }

    public static String shortenText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 시드 요약문에서 내부 분석 토큰을 제거하고 사용자 설명형 문장으로 정리한다.
     */
    public static String normalizeSummarySeed(String text) {
        String normalized = safeText(text).replaceAll("\\s+", " ").trim();
        normalized = normalized
                .replace("메서드 method:", "메서드 ")
                .replace("메서드 ctor:", "생성자 ")
                .replace("method:", "")
                .replace("type:", "")
                .replace("ctor:", "")
                .replace("guard return 후보", "입력 조건 기반 조기 반환 로직")
                .replace("guard clause 후보", "입력 조건 기반 예외/반환 분기")
                .replace("조건문 이후 return 또는 error response가 근접하게 나타나는", "입력 조건에 따라 조기 반환되거나 오류가 발생할 수 있는")
                .replace("조건문 이후 예외 throw가 근접하게 나타나는", "입력 조건에 따라 예외가 발생할 수 있는")
                .replace("require/assert/check 계열의 전제조건 검증 호출이 발견되었습니다.", "입력값 전제조건을 검증합니다.")
                .replace("delete 성격의 repository/entity manager 호출이 발견되", "상태를 제거하거나 정리하는 호출이 포함되");
        if (normalized.isBlank()) {
            return "핵심 동작을 수행합니다.";
        }
        return normalized;
    }

    public static double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    public static boolean isBytecodeFilePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.endsWith(".class")
                || lower.contains("/target/classes/")
                || lower.contains("/build/classes/java/main/")
                || lower.contains("/build/classes/kotlin/main/");
    }
}


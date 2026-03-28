package com.example.ossdoc.domain.extraction.service.support.planning;
import com.example.ossdoc.domain.extraction.service.support.util.RepoPathUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 경로 prefix / glob 매칭 유틸
 *
 * 지원 예:
 * - src/main/java
 * - module-a/src/main/java
 * - {@code **}/*.java
 * - {@code **}/generated/{@code **}
 */
public class IncludeGlobMatcher {

    private final List<String> normalizedPrefixes;
    private final List<Pattern> globPatterns;

    public IncludeGlobMatcher(List<String> includeGlobs) {
        this.normalizedPrefixes = new ArrayList<>();
        this.globPatterns = new ArrayList<>();

        if (includeGlobs == null || includeGlobs.isEmpty()) {
            return;
        }

        for (String glob : includeGlobs) {
            if (glob == null || glob.isBlank()) {
                continue;
            }

            String normalized = normalizeGlob(glob);
            if (containsGlobToken(normalized)) {
                globPatterns.add(Pattern.compile(toRegex(normalized)));
            } else {
                normalizedPrefixes.add(trimTrailingSlash(normalized));
            }
        }
    }

    public boolean hasRules() {
        return !(normalizedPrefixes.isEmpty() && globPatterns.isEmpty());
    }

    public boolean matches(Path relativePath) {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        return matches(relativePath.toString());
    }

    public boolean matches(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath must not be null");

        if (!hasRules()) {
            return true;
        }

        String normalizedPath = trimTrailingSlash(RepoPathUtils.normalizeSeparators(relativePath));

        for (String prefix : normalizedPrefixes) {
            if (normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/")) {
                return true;
            }
        }

        for (Pattern pattern : globPatterns) {
            if (pattern.matcher(normalizedPath).matches()) {
                return true;
            }
        }

        return false;
    }

    private static String normalizeGlob(String value) {
        return trimTrailingSlash(RepoPathUtils.normalizeSeparators(value.trim()));
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean containsGlobToken(String value) {
        return value.contains("*")
                || value.contains("?")
                || value.contains("[")
                || value.contains("]")
                || value.contains("{")
                || value.contains("}");
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");

        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);

            if (ch == '*') {
                boolean doubleStar = (i + 1 < glob.length()) && glob.charAt(i + 1) == '*';
                if (doubleStar) {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
                continue;
            }

            if (ch == '?') {
                regex.append("[^/]");
                continue;
            }

            if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
                regex.append("\\");
            }
            regex.append(ch);
        }

        regex.append("$");
        return regex.toString();
    }
}

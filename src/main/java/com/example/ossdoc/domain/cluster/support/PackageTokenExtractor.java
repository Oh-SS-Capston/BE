package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class PackageTokenExtractor {
    private static final Set<String> STOPWORDS = Set.of(
            "com", "org", "net", "io", "dev",
            "example", "sample", "seed", "demo", "test"); //해당 토큰들은 이름 생성에서 제외

    public String labelOf(List<ProjectedNode> members) {
        List<String> packageRoots = members.stream()
                .map(ProjectedNode::getPackageName)
                .filter(pkg -> pkg != null && !pkg.isBlank())
                .distinct()
                .sorted()
                .toList();

        String rootBased = labelFromPackageRoots(packageRoots);
        if (rootBased != null) {
            return rootBased;
        }

        Map<String, Integer> counter = new HashMap<>();
        for (ProjectedNode member : members) {
            String packageName = member.getPackageName();
            if (packageName == null || packageName.isBlank()) continue;

            for (String token : packageName.split("\\.")) {
                String normalized = token.toLowerCase(Locale.ROOT);
                if (normalized.length() < 3) continue;
                if (STOPWORDS.contains(normalized)) continue;
                counter.merge(normalized, 1, Integer::sum);
            }
        }

        List<String> top = counter.entrySet().stream()
                .sorted((a, b) -> {
                    int byCount = Integer.compare(b.getValue(), a.getValue());
                    if(byCount != 0) return byCount;
                    return a.getKey().compareTo(b.getKey());
                })
                .limit(2)
                .map(Map.Entry::getKey)
                .toList();

        return top.isEmpty()? "subsystem" : String.join("/", top);
    }

    private String labelFromPackageRoots(List<String> packageRoots) {
        if (packageRoots.isEmpty()) return null;

        String[] first = packageRoots.get(0).split("\\.");
        int prefixLen = first.length;

        for (int i = 1; i < packageRoots.size(); i++) {
            String[] current = packageRoots.get(i).split("\\.");
            prefixLen = Math.min(prefixLen, current.length);
            for (int j = 0; j < prefixLen; j++) {
                if (!Objects.equals(first[j], current[j])) {
                    prefixLen = j;
                    break;
                }
            }
        }
        if (prefixLen == 0) return null;

        for (int i = prefixLen - 1; i >= 0; i--) {
            String token = first[i].toLowerCase(Locale.ROOT);
            if (!STOPWORDS.contains(token) && token.length() >= 3) {
                return token;
            }
        }
        return null;
    }
}
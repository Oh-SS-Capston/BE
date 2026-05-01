package com.example.ossdoc.domain.cluster.support;

import com.example.ossdoc.domain.cluster.model.ProjectedNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class PackageTokenExtractor {

    public String labelOf(List<ProjectedNode> members) {
        Map<String, Integer> counter = new HashMap<>();

        for (ProjectedNode member : members) {
            if (member.getPackageName() == null || member.getPackageName().isBlank()) continue;

            String[] tokens = member.getPackageName().split("\\.");
            for (String token : tokens) {
                if (token.length() < 3) continue;
                counter.merge(token.toLowerCase(), 1, Integer::sum);
            }
        }

        List<String> top = counter.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (top.isEmpty()) {
            return "subsystem";
        }

        return String.join(" / ", top);
    }
}
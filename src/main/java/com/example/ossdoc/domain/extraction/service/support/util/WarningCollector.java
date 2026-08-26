package com.example.ossdoc.domain.extraction.service.support.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * warnings 중복 제거 + 순서 보존
 */
public class WarningCollector {

    private final Set<String> warnings = new LinkedHashSet<>();

    public void add(String warning) {
        if (warning == null || warning.isBlank()) {
            return;
        }
        warnings.add(warning);
    }

    public void addAll(List<String> warningList) {
        if (warningList == null || warningList.isEmpty()) {
            return;
        }
        for (String warning : warningList) {
            add(warning);
        }
    }

    public boolean isEmpty() {
        return warnings.isEmpty();
    }

    public List<String> snapshot() {
        return new ArrayList<>(warnings);
    }
}
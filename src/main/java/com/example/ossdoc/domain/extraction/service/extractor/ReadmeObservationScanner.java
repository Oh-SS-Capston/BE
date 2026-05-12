package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ExtractionAggregate;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.enums.AccessLevel;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.service.support.util.EvidenceIdGenerator;
import com.example.ossdoc.domain.extraction.service.support.util.RepoPathUtils;
import com.example.ossdoc.domain.extraction.service.support.util.SymbolIdFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * README 코드 블록에서 대문자 시작 식별자를 추출해 public 타입 목록과 대조한다.
 * 단일 매칭 시 README_MENTION ObservationFact를 생성한다.
 * 청크 병합 완료 후 호출해야 public surface가 확보된다.
 */
@Slf4j
@Component
public class ReadmeObservationScanner {

    private static final List<String> README_CANDIDATES = List.of(
            "README.md", "QUICKSTART.md", "docs/usage.md", "docs/getting-started.md"
    );

    private static final Pattern CODE_FENCE =
            Pattern.compile("```[\\w]*\\n([\\s\\S]*?)```", Pattern.MULTILINE);

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Z][A-Za-z0-9]{1,}");

    private static final Pattern SECTION_HEADER =
            Pattern.compile("(?i)^#{1,3}\\s+(quick\\s+start|getting\\s+started|usage|example|tutorial)",
                    Pattern.MULTILINE);

    private static final Set<String> EXCLUDED = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Object",
            "List", "Map", "Set", "Optional", "Stream", "Collection", "Iterable",
            "Exception", "RuntimeException", "Override", "Deprecated", "SuppressWarnings",
            "System", "Thread", "Class", "Enum", "Number", "Math", "Arrays", "Collections",
            "StringBuilder", "StringBuffer", "Runnable", "Comparable", "Serializable",
            "Void", "Byte", "Short", "Character"
    );

    public void scan(Path repoRoot, ExtractionAggregate aggregate, ExtractionSink sink) {
        Path readmeFile = findReadme(repoRoot);
        if (readmeFile == null) {
            return;
        }

        Map<String, String> publicSurface = buildPublicSurface(aggregate);
        if (publicSurface.isEmpty()) {
            return;
        }

        String content;
        try {
            content = Files.readString(readmeFile);
        } catch (IOException e) {
            log.warn("[README-SCAN] README 읽기 실패: {}", readmeFile, e);
            return;
        }

        String relativePath = RepoPathUtils.toRepoRelative(repoRoot, readmeFile);
        String siteSymbol = SymbolIdFactory.module("default");

        Set<String> found = extractIdentifiers(content);

        for (String id : found) {
            if (!publicSurface.containsKey(id)) continue;
            String fqcn = publicSurface.get(id);
            if (fqcn == null) continue; // 동명 타입 복수 — 오탐 방지

            EvidenceFact evidence = EvidenceFact.builder()
                    .id(EvidenceIdGenerator.generate(EvidenceType.README, relativePath,
                            null, null, null, null, fqcn))
                    .type(EvidenceType.README)
                    .path(relativePath)
                    .symbol(fqcn)
                    .build();
            sink.addEvidence(evidence);

            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.README_MENTION)
                    .siteSymbol(siteSymbol)
                    .targetSymbol(fqcn)
                    .origin(FactOriginKind.RESOURCE)
                    .confidenceHint(0.6)
                    .evidenceIds(List.of(evidence.id()))
                    .build());
        }
    }

    private Map<String, String> buildPublicSurface(ExtractionAggregate aggregate) {
        Map<String, String> surface = new HashMap<>();
        if (aggregate.symbols() == null || aggregate.symbols().types() == null) {
            return surface;
        }
        for (SymbolFact type : aggregate.symbols().types()) {
            if (type.access() != AccessLevel.PUBLIC) continue;
            String qn = type.qualifiedName();
            if (qn == null || qn.isBlank()) continue;
            String simpleName = simpleName(qn);
            if (surface.containsKey(simpleName)) {
                surface.put(simpleName, null); // 동명 타입 충돌 표시
            } else {
                surface.put(simpleName, qn);
            }
        }
        return surface;
    }

    private Set<String> extractIdentifiers(String content) {
        String targetContent = scopeToTargetSections(content);
        Set<String> identifiers = new HashSet<>();
        Matcher fence = CODE_FENCE.matcher(targetContent);
        while (fence.find()) {
            Matcher id = IDENTIFIER.matcher(fence.group(1));
            while (id.find()) {
                String token = id.group();
                if (!EXCLUDED.contains(token)) {
                    identifiers.add(token);
                }
            }
        }
        return identifiers;
    }

    private String scopeToTargetSections(String content) {
        Matcher header = SECTION_HEADER.matcher(content);
        if (!header.find()) {
            return content;
        }
        // 첫 번째 매칭 헤더 이후 내용만 반환
        return content.substring(header.start());
    }

    private Path findReadme(Path repoRoot) {
        for (String candidate : README_CANDIDATES) {
            Path p = repoRoot.resolve(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static String simpleName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }
}

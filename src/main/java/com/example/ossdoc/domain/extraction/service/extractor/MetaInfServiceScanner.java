package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.service.support.util.EvidenceIdGenerator;
import com.example.ossdoc.domain.extraction.service.support.util.RepoPathUtils;
import com.example.ossdoc.domain.extraction.service.support.util.SymbolIdFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * META-INF/services 파일을 스캔해 SPI_PROVIDER ObservationFact를 생성한다.
 * 소스 resourceRoot를 우선 탐색하고, 없으면 repoRoot 전체를 fallback 탐색한다.
 * 빌드 출력 경로(target/, build/, .gradle/)는 제외한다.
 */
@Slf4j
@Component
public class MetaInfServiceScanner {

    private static final String SERVICES_SUBDIR = "META-INF/services";
    private static final Set<String> BUILD_SEGMENTS = Set.of("target", "build", ".gradle");

    public void scan(Path repoRoot, List<BuildModuleManifest> modules, ExtractionSink sink) {
        Set<String> seenInterfaces = new HashSet<>();

        if (modules != null) {
            for (BuildModuleManifest module : modules) {
                if (module == null) continue;
                String siteSymbol = SymbolIdFactory.module(resolveModuleName(module));
                List<String> resourceRoots = module.getResourceRoots();
                if (resourceRoots == null) continue;
                for (String root : resourceRoots) {
                    if (root == null || root.isBlank()) continue;
                    Path servicesDir = resolveRootPath(repoRoot, root).resolve(SERVICES_SUBDIR);
                    if (!Files.isDirectory(servicesDir) || isBuildOutput(servicesDir)) continue;
                    scanServicesDir(repoRoot, servicesDir, siteSymbol, seenInterfaces, sink);
                }
            }
        }

        if (seenInterfaces.isEmpty()) {
            scanFallback(repoRoot, sink, seenInterfaces);
        }
    }

    private void scanServicesDir(Path repoRoot, Path servicesDir, String siteSymbol,
                                  Set<String> seen, ExtractionSink sink) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(servicesDir)) {
            for (Path file : entries) {
                if (!Files.isRegularFile(file)) continue;
                String interfaceFqcn = file.getFileName().toString();
                if (interfaceFqcn.isBlank() || seen.contains(interfaceFqcn)) continue;
                seen.add(interfaceFqcn);

                String relativePath = RepoPathUtils.toRepoRelative(repoRoot, file);
                emitSpiProviderFacts(siteSymbol, interfaceFqcn, relativePath, sink);
            }
        } catch (IOException e) {
            log.warn("[META-INF-SCAN] services 디렉터리 읽기 실패: {}", servicesDir, e);
        }
    }

    private void scanFallback(Path repoRoot, ExtractionSink sink, Set<String> seen) {
        try (var paths = Files.walk(repoRoot)) {
            paths.filter(p -> Files.isDirectory(p)
                           && p.endsWith(Path.of(SERVICES_SUBDIR))
                           && !isBuildOutput(p))
                 .forEach(servicesDir -> {
                     String siteSymbol = SymbolIdFactory.module("default");
                     scanServicesDir(repoRoot, servicesDir, siteSymbol, seen, sink);
                 });
        } catch (IOException e) {
            log.warn("[META-INF-SCAN] 레포 루트 탐색 실패: {}", repoRoot, e);
        }
    }

    private void emitSpiProviderFacts(String siteSymbol, String interfaceFqcn,
                                       String relativePath, ExtractionSink sink) {
        EvidenceFact evidence = EvidenceFact.builder()
                .id(EvidenceIdGenerator.generate(EvidenceType.RESOURCE, relativePath,
                        null, null, null, null, interfaceFqcn))
                .type(EvidenceType.RESOURCE)
                .path(relativePath)
                .symbol(interfaceFqcn)
                .build();
        sink.addEvidence(evidence);

        ObservationFact observation = ObservationFact.builder()
                .kind(ObservationKind.SPI_PROVIDER)
                .siteSymbol(siteSymbol)
                .targetSymbol(interfaceFqcn)
                .origin(FactOriginKind.RESOURCE)
                .confidenceHint(0.9)
                .evidenceIds(List.of(evidence.id()))
                .build();
        sink.addObservation(observation);
    }

    private static boolean isBuildOutput(Path path) {
        for (Path segment : path) {
            if (BUILD_SEGMENTS.contains(segment.toString())) return true;
        }
        return false;
    }

    private static Path resolveRootPath(Path repoRoot, String rawPath) {
        Path p = Path.of(rawPath);
        return p.isAbsolute() ? p.normalize() : repoRoot.resolve(p).normalize();
    }

    private static String resolveModuleName(BuildModuleManifest module) {
        if (module.getModuleId() != null && !module.getModuleId().isBlank()) {
            return module.getModuleId();
        }
        if (module.getName() != null && !module.getName().isBlank()) {
            return module.getName();
        }
        return "default";
    }
}

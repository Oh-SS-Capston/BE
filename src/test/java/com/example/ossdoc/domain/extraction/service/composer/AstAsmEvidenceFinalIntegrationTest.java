package com.example.ossdoc.domain.extraction.service.composer;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.service.extractor.AsmBytecodeFactsExtractor;
import com.example.ossdoc.domain.extraction.service.extractor.JavaParserAstFactsExtractor;
import com.example.ossdoc.domain.extraction.service.support.evidence.EvidenceMergePolicy;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AstAsmEvidenceFinalIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("동일 CALLS 관계 병합 시 AST 표현식과 ASM instruction Evidence를 모두 유지한다")
    void callRelationKeepsAstAndAsmEvidence()
            throws Exception {
        ExtractionPair extraction = extractFixture();

        RelationFact astCall = findRelation(
                extraction.astRelations().calls(),
                relation ->
                        contains(
                                relation.dstSymbol(),
                                "sample.Target#call"
                        )
        );

        RelationFact asmCall = findRelation(
                extraction.asmRelations().calls(),
                relation ->
                        contains(
                                relation.dstSymbol(),
                                "sample.Target#call"
                        )
        );

        assertEquals(astCall.srcSymbol(), asmCall.srcSymbol());
        assertEquals(astCall.dstSymbol(), asmCall.dstSymbol());

        RelationFact merged =
                FactsDedupSupport.mergeRelation(
                        astCall,
                        asmCall
                );

        assertEquals(
                FactOriginKind.AST_AND_BYTECODE,
                merged.origin()
        );
        assertEquals(2, merged.evidenceIds().size());

        Set<String> contracts = evidenceContracts(
                extraction.evidence(),
                merged.evidenceIds()
        );

        assertEquals(
                Set.of(
                        "AST:expression:method_call",
                        "BYTECODE:instruction:method_call"
                ),
                contracts
        );

        assertAllEvidenceResolvable(
                extraction.evidence(),
                merged.evidenceIds()
        );

        EvidenceFact astEvidence = findEvidence(
                extraction.evidence(),
                merged.evidenceIds(),
                evidence ->
                        evidence.type() == EvidenceType.AST
        );

        EvidenceFact asmEvidence = findEvidence(
                extraction.evidence(),
                merged.evidenceIds(),
                evidence ->
                        evidence.type()
                                == EvidenceType.BYTECODE
        );

        assertEquals(
                "target.call()",
                astEvidence.snippet()
        );
        assertTrue(
                asmEvidence.snippet()
                        .startsWith("INVOKEVIRTUAL")
        );
        assertEquals(
                "call",
                asmEvidence.attrs().get("member_name")
        );
        assertNotNull(
                asmEvidence.attrs().get("instruction_index")
        );
        assertNotNull(merged.callSiteLine());
    }

    @Test
    @DisplayName("동일 ANNOTATED_WITH 관계 병합 시 AST·BYTECODE annotation Evidence를 모두 유지한다")
    void annotationRelationKeepsAstAndAsmEvidence()
            throws Exception {
        ExtractionPair extraction = extractFixture();

        RelationFact astAnnotation = findRelation(
                extraction.astRelations().annotatedWith(),
                relation ->
                        contains(
                                relation.srcSymbol(),
                                "type:sample.Sample"
                        )
                                && contains(
                                relation.dstSymbol(),
                                "sample.Marker"
                        )
        );

        RelationFact asmAnnotation = findRelation(
                extraction.asmRelations().annotatedWith(),
                relation ->
                        contains(
                                relation.srcSymbol(),
                                "type:sample.Sample"
                        )
                                && contains(
                                relation.dstSymbol(),
                                "sample.Marker"
                        )
        );

        assertEquals(
                astAnnotation.srcSymbol(),
                asmAnnotation.srcSymbol()
        );
        assertEquals(
                astAnnotation.dstSymbol(),
                asmAnnotation.dstSymbol()
        );

        RelationFact merged =
                FactsDedupSupport.mergeRelation(
                        astAnnotation,
                        asmAnnotation
                );

        assertEquals(
                FactOriginKind.AST_AND_BYTECODE,
                merged.origin()
        );

        assertEquals(
                Set.of(
                        "AST:annotation:annotation",
                        "BYTECODE:annotation:annotation"
                ),
                evidenceContracts(
                        extraction.evidence(),
                        merged.evidenceIds()
                )
        );

        EvidenceFact astEvidence = findEvidence(
                extraction.evidence(),
                merged.evidenceIds(),
                evidence ->
                        evidence.type() == EvidenceType.AST
        );

        EvidenceFact asmEvidence = findEvidence(
                extraction.evidence(),
                merged.evidenceIds(),
                evidence ->
                        evidence.type()
                                == EvidenceType.BYTECODE
        );

        assertEquals("@Marker", astEvidence.snippet());
        assertEquals(
                "@sample.Marker",
                asmEvidence.snippet()
        );
        assertEquals(
                "sample.Marker",
                asmEvidence.attrs().get("annotation_name")
        );
    }

    @Test
    @DisplayName("Event 구독 Observation 병합 시 AST 어노테이션·파라미터와 ASM 어노테이션을 모두 유지한다")
    void eventSubscriptionKeepsAllEvidenceRoles()
            throws Exception {
        ExtractionPair extraction = extractFixture();

        ObservationFact astSubscription =
                extraction.astObservations()
                        .eventSubscriptions()
                        .get(0);

        ObservationFact asmSubscription =
                extraction.asmObservations()
                        .eventSubscriptions()
                        .get(0);

        assertEquals(
                astSubscription.siteSymbol(),
                asmSubscription.siteSymbol()
        );
        assertNotNull(astSubscription.targetTypeRef());
        assertNotNull(asmSubscription.targetTypeRef());
        assertEquals(
                astSubscription.targetTypeRef().raw(),
                asmSubscription.targetTypeRef().raw()
        );

        ObservationFact merged =
                FactsDedupSupport.mergeObservation(
                        astSubscription,
                        asmSubscription
                );

        assertEquals(
                FactOriginKind.OBSERVED,
                merged.origin()
        );

        assertEquals(
                Set.of(
                        "AST:annotation:event_subscription",
                        "AST:parameter:event_payload_parameter",
                        "BYTECODE:annotation:event_subscription"
                ),
                evidenceContracts(
                        extraction.evidence(),
                        merged.evidenceIds()
                )
        );

        assertEquals(3, merged.evidenceIds().size());
        assertAllEvidenceResolvable(
                extraction.evidence(),
                merged.evidenceIds()
        );

        assertTrue(
                evidenceFor(
                        extraction.evidence(),
                        merged.evidenceIds()
                ).stream().anyMatch(evidence ->
                        "OrderCreatedEvent event".equals(
                                evidence.snippet()
                        )
                )
        );

        assertFalse(
                evidenceFor(
                        extraction.evidence(),
                        merged.evidenceIds()
                ).stream().anyMatch(evidence ->
                        "member".equals(
                                String.valueOf(
                                        evidence.attrs()
                                                .get("granularity")
                                )
                        )
                ),
                "의미 Observation이 메서드 전체 Evidence로 회귀하면 안 됨"
        );
    }

    @Test
    @DisplayName("최종 Evidence map에는 병합된 관계·Observation의 모든 ID가 존재하고 출처별 ID가 충돌하지 않는다")
    void finalEvidenceMapHasNoDanglingOrCollidingIds()
            throws Exception {
        ExtractionPair extraction = extractFixture();

        RelationFact mergedCall =
                FactsDedupSupport.mergeRelation(
                        findRelation(
                                extraction.astRelations().calls(),
                                relation ->
                                        contains(
                                                relation.dstSymbol(),
                                                "sample.Target#call"
                                        )
                        ),
                        findRelation(
                                extraction.asmRelations().calls(),
                                relation ->
                                        contains(
                                                relation.dstSymbol(),
                                                "sample.Target#call"
                                        )
                        )
                );

        RelationFact mergedAnnotation =
                FactsDedupSupport.mergeRelation(
                        findRelation(
                                extraction.astRelations()
                                        .annotatedWith(),
                                relation ->
                                        contains(
                                                relation.dstSymbol(),
                                                "sample.Marker"
                                        )
                        ),
                        findRelation(
                                extraction.asmRelations()
                                        .annotatedWith(),
                                relation ->
                                        contains(
                                                relation.dstSymbol(),
                                                "sample.Marker"
                                        )
                        )
                );

        ObservationFact mergedSubscription =
                FactsDedupSupport.mergeObservation(
                        extraction.astObservations()
                                .eventSubscriptions()
                                .get(0),
                        extraction.asmObservations()
                                .eventSubscriptions()
                                .get(0)
                );

        LinkedHashSet<String> referencedIds =
                new LinkedHashSet<>();

        referencedIds.addAll(mergedCall.evidenceIds());
        referencedIds.addAll(
                mergedAnnotation.evidenceIds()
        );
        referencedIds.addAll(
                mergedSubscription.evidenceIds()
        );

        assertEquals(7, referencedIds.size());
        assertAllEvidenceResolvable(
                extraction.evidence(),
                List.copyOf(referencedIds)
        );

        long astCount = evidenceFor(
                extraction.evidence(),
                List.copyOf(referencedIds)
        ).stream()
                .filter(evidence ->
                        evidence.type() == EvidenceType.AST
                )
                .count();

        long bytecodeCount = evidenceFor(
                extraction.evidence(),
                List.copyOf(referencedIds)
        ).stream()
                .filter(evidence ->
                        evidence.type()
                                == EvidenceType.BYTECODE
                )
                .count();

        assertEquals(4, astCount);
        assertEquals(3, bytecodeCount);
    }

    private ExtractionPair extractFixture()
            throws Exception {
        FixturePaths paths = createAndCompileFixture();

        ExtractionContext context =
                extractionContext(paths);

        ExtractionSink astSink = new ExtractionSink();
        extractAst(
                context,
                paths.sourceRoot(),
                paths.sampleSource(),
                astSink
        );

        ExtractionSink asmSink = new ExtractionSink();
        extractAsm(
                context,
                paths.sampleClass(),
                asmSink
        );

        var astFacts = astSink.toExtractedFacts();
        var asmFacts = asmSink.toExtractedFacts();

        Map<String, EvidenceFact> evidence =
                new LinkedHashMap<>();

        EvidenceMergePolicy.mergeInto(
                evidence,
                astFacts.evidence()
        );
        EvidenceMergePolicy.mergeInto(
                evidence,
                asmFacts.evidence()
        );

        assertEquals(
                astFacts.evidence().size()
                        + asmFacts.evidence().size(),
                evidence.size(),
                "AST와 ASM의 역할별 Evidence ID가 충돌하면 안 됨"
        );

        return new ExtractionPair(
                evidence,
                astFacts.relations(),
                asmFacts.relations(),
                astFacts.observations(),
                asmFacts.observations()
        );
    }

    private ExtractionContext extractionContext(
            FixturePaths paths
    ) {
        ExtractionContext context =
                mock(ExtractionContext.class);

        when(context.includeObservations())
                .thenReturn(true);
        when(context.module())
                .thenReturn("sample-app");
        when(context.sourceRootString())
                .thenReturn(
                        paths.sourceRoot().toString()
                );
        when(context.bytecodeRootString())
                .thenReturn(
                        paths.classesRoot().toString()
                );
        when(context.repoRoot())
                .thenReturn(tempDir);

        return context;
    }

    private void extractAst(
            ExtractionContext context,
            Path sourceRoot,
            Path sampleSource,
            ExtractionSink sink
    ) throws Exception {
        JavaParser parser = javaParser(sourceRoot);

        CompilationUnit unit =
                parser.parse(sampleSource)
                        .getResult()
                        .orElseThrow();

        TypeDeclaration<?> sampleType =
                unit.getTypes()
                        .stream()
                        .filter(type ->
                                "Sample".equals(
                                        type.getNameAsString()
                                )
                        )
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Sample 타입을 찾을 수 없습니다."
                                )
                        );

        Method collectType =
                JavaParserAstFactsExtractor.class
                        .getDeclaredMethod(
                                "collectTypeRecursive",
                                ExtractionContext.class,
                                String.class,
                                String.class,
                                String.class,
                                TypeDeclaration.class,
                                List.class,
                                ExtractionSink.class
                        );

        collectType.setAccessible(true);
        collectType.invoke(
                new JavaParserAstFactsExtractor(),
                context,
                "src/main/java/sample/Sample.java",
                "package:sample",
                null,
                sampleType,
                Files.readAllLines(
                        sampleSource,
                        StandardCharsets.UTF_8
                ),
                sink
        );
    }

    private void extractAsm(
            ExtractionContext context,
            Path sampleClass,
            ExtractionSink sink
    ) throws Exception {
        Method visitClassFile =
                AsmBytecodeFactsExtractor.class
                        .getDeclaredMethod(
                                "visitClassFile",
                                ExtractionContext.class,
                                Path.class,
                                ExtractionSink.class
                        );

        visitClassFile.setAccessible(true);
        visitClassFile.invoke(
                new AsmBytecodeFactsExtractor(),
                context,
                sampleClass,
                sink
        );
    }

    private JavaParser javaParser(Path sourceRoot) {
        CombinedTypeSolver typeSolver =
                new CombinedTypeSolver();

        typeSolver.add(
                new ReflectionTypeSolver(false)
        );
        typeSolver.add(
                new JavaParserTypeSolver(sourceRoot)
        );

        ParserConfiguration configuration =
                new ParserConfiguration()
                        .setLanguageLevel(
                                ParserConfiguration
                                        .LanguageLevel
                                        .BLEEDING_EDGE
                        )
                        .setSymbolResolver(
                                new JavaSymbolSolver(typeSolver)
                        );

        return new JavaParser(configuration);
    }

    private FixturePaths createAndCompileFixture()
            throws Exception {
        Path sourceRoot =
                tempDir.resolve("src/main/java");
        Path packageDir =
                sourceRoot.resolve("sample");
        Path classesRoot =
                tempDir.resolve("build/classes/java/main");

        Files.createDirectories(packageDir);
        Files.createDirectories(classesRoot);

        Path markerSource = writeSource(
                packageDir,
                "Marker.java",
                """
                        package sample;

                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;

                        @Retention(RetentionPolicy.RUNTIME)
                        @Target(ElementType.TYPE)
                        public @interface Marker {
                        }
                        """
        );

        Path eventListenerSource = writeSource(
                packageDir,
                "EventListener.java",
                """
                        package sample;

                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Retention;
                        import java.lang.annotation.RetentionPolicy;
                        import java.lang.annotation.Target;

                        @Retention(RetentionPolicy.RUNTIME)
                        @Target(ElementType.METHOD)
                        public @interface EventListener {
                        }
                        """
        );

        Path eventSource = writeSource(
                packageDir,
                "OrderCreatedEvent.java",
                """
                        package sample;

                        public class OrderCreatedEvent {
                        }
                        """
        );

        Path targetSource = writeSource(
                packageDir,
                "Target.java",
                """
                        package sample;

                        public class Target {

                            public String call() {
                                return "ok";
                            }
                        }
                        """
        );

        Path sampleSource = writeSource(
                packageDir,
                "Sample.java",
                """
                        package sample;

                        @Marker
                        public class Sample {

                            private String value;

                            public void run(Target target) {
                                this.value = target.call();
                            }

                            @EventListener
                            public void handle(
                                    OrderCreatedEvent event
                            ) {
                            }
                        }
                        """
        );

        JavaCompiler compiler =
                ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "JDK JavaCompiler가 필요함"
        );

        List<String> arguments =
                new ArrayList<>(List.of(
                        "-g",
                        "-parameters",
                        "-encoding",
                        "UTF-8",
                        "-d",
                        classesRoot.toString()
                ));

        arguments.add(markerSource.toString());
        arguments.add(eventListenerSource.toString());
        arguments.add(eventSource.toString());
        arguments.add(targetSource.toString());
        arguments.add(sampleSource.toString());

        int exitCode = compiler.run(
                null,
                null,
                null,
                arguments.toArray(String[]::new)
        );

        assertEquals(
                0,
                exitCode,
                "fixture 컴파일 실패"
        );

        return new FixturePaths(
                sourceRoot,
                classesRoot,
                sampleSource,
                classesRoot.resolve(
                        "sample/Sample.class"
                )
        );
    }

    private Path writeSource(
            Path packageDir,
            String fileName,
            String source
    ) throws Exception {
        Path path = packageDir.resolve(fileName);

        Files.writeString(
                path,
                source,
                StandardCharsets.UTF_8
        );

        return path;
    }

    private RelationFact findRelation(
            List<RelationFact> relations,
            Predicate<RelationFact> predicate
    ) {
        return relations.stream()
                .filter(predicate)
                .findFirst()
                .orElseThrow();
    }

    private EvidenceFact findEvidence(
            Map<String, EvidenceFact> evidence,
            List<String> evidenceIds,
            Predicate<EvidenceFact> predicate
    ) {
        return evidenceFor(evidence, evidenceIds)
                .stream()
                .filter(predicate)
                .findFirst()
                .orElseThrow();
    }

    private List<EvidenceFact> evidenceFor(
            Map<String, EvidenceFact> evidence,
            List<String> evidenceIds
    ) {
        return evidenceIds.stream()
                .map(evidence::get)
                .toList();
    }

    private Set<String> evidenceContracts(
            Map<String, EvidenceFact> evidence,
            List<String> evidenceIds
    ) {
        return evidenceFor(evidence, evidenceIds)
                .stream()
                .map(item ->
                        item.type().name()
                                + ":"
                                + item.attrs().get(
                                "granularity"
                        )
                                + ":"
                                + item.attrs().get("role")
                )
                .collect(Collectors.toSet());
    }

    private void assertAllEvidenceResolvable(
            Map<String, EvidenceFact> evidence,
            List<String> evidenceIds
    ) {
        for (String evidenceId : evidenceIds) {
            assertNotNull(
                    evidence.get(evidenceId),
                    () -> "참조 Evidence 누락: "
                            + evidenceId
            );
        }
    }

    private boolean contains(
            String value,
            String expected
    ) {
        return value != null
                && value.contains(expected);
    }

    private record FixturePaths(
            Path sourceRoot,
            Path classesRoot,
            Path sampleSource,
            Path sampleClass
    ) {
    }

    private record ExtractionPair(
            Map<String, EvidenceFact> evidence,
            com.example.ossdoc.domain.extraction.dto.model.RelationTable astRelations,
            com.example.ossdoc.domain.extraction.dto.model.RelationTable asmRelations,
            com.example.ossdoc.domain.extraction.dto.model.ObservationTable astObservations,
            com.example.ossdoc.domain.extraction.dto.model.ObservationTable asmObservations
    ) {
    }
}

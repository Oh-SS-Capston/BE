package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserStructuralRelationEvidenceTest {

    private static final String OWNER_SYMBOL =
            "method:sample.Sample#run()";

    private final JavaParserAstFactsExtractor extractor =
            new JavaParserAstFactsExtractor();

    @Test
    @DisplayName("CALLS 관계가 메서드 호출식 범위 Evidence를 참조한다")
    void callsUsesMethodCallEvidence() throws Exception {
        String source = """
                class Sample {
                    void run() {
                        target.call(); other.call();
                    }
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        MethodCallExpr call = unit.findAll(MethodCallExpr.class)
                .stream()
                .filter(candidate ->
                        candidate.toString().equals("target.call()"))
                .findFirst()
                .orElseThrow();

        ExtractionSink sink = new ExtractionSink();
        String fallbackEvidenceId = "member-evidence";

        invoke(
                "addMethodCallRelation",
                new Class<?>[]{
                        String.class,
                        MethodCallExpr.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                OWNER_SYMBOL,
                call,
                "src/main/java/sample/Sample.java",
                source.lines().toList(),
                fallbackEvidenceId,
                sink
        );

        RelationFact relation = sink.toExtractedFacts()
                .relations()
                .calls()
                .get(0);

        EvidenceFact evidence = evidenceOf(
                sink,
                relation.evidenceIds().get(0)
        );

        assertNotEquals(
                fallbackEvidenceId,
                relation.evidenceIds().get(0)
        );
        assertEquals("target.call()", evidence.snippet());
        assertEquals(
                "expression",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                "method_call",
                evidence.attrs().get("role")
        );
        assertFalse(evidence.snippet().contains("other.call()"));
        assertFalse(evidence.snippet().endsWith(";"));
    }

    @Test
    @DisplayName("CREATES 관계가 new 표현식 범위 Evidence를 참조한다")
    void createsUsesObjectCreationEvidence() throws Exception {
        String source = """
                class Sample {
                    void run() {
                        Object first = new String("x"); Object second = new Object();
                    }
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        ObjectCreationExpr creation = unit
                .findAll(ObjectCreationExpr.class)
                .stream()
                .filter(candidate ->
                        candidate.toString().equals("new String(\"x\")"))
                .findFirst()
                .orElseThrow();

        ExtractionSink sink = new ExtractionSink();

        invoke(
                "addObjectCreationRelation",
                new Class<?>[]{
                        String.class,
                        ObjectCreationExpr.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                OWNER_SYMBOL,
                creation,
                "src/main/java/sample/Sample.java",
                source.lines().toList(),
                "member-evidence",
                sink
        );

        RelationFact relation = sink.toExtractedFacts()
                .relations()
                .creates()
                .get(0);

        EvidenceFact evidence = evidenceOf(
                sink,
                relation.evidenceIds().get(0)
        );

        assertEquals("new String(\"x\")", evidence.snippet());
        assertEquals(
                "expression",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                "object_creation",
                evidence.attrs().get("role")
        );
        assertFalse(evidence.snippet().contains("new Object()"));
        assertFalse(evidence.snippet().endsWith(";"));
    }

    @Test
    @DisplayName("ACCESSES_FIELD 관계가 실제 필드 접근식 범위 Evidence를 참조한다")
    void fieldAccessUsesExpressionEvidence() throws Exception {
        String source = """
                class Sample {
                    void run() {
                        System.out.println("hello");
                    }
                }
                """;

        ParserConfiguration configuration =
                new ParserConfiguration()
                        .setSymbolResolver(
                                new JavaSymbolSolver(
                                        new ReflectionTypeSolver(false)
                                )
                        );

        CompilationUnit unit = new JavaParser(configuration)
                .parse(source)
                .getResult()
                .orElseThrow();

        FieldAccessExpr fieldAccess = unit
                .findAll(FieldAccessExpr.class)
                .stream()
                .filter(candidate ->
                        candidate.toString().equals("System.out"))
                .findFirst()
                .orElseThrow();

        ExtractionSink sink = new ExtractionSink();

        invoke(
                "addFieldAccessRelation",
                new Class<?>[]{
                        String.class,
                        com.github.javaparser.ast.expr.Expression.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                OWNER_SYMBOL,
                fieldAccess,
                "src/main/java/sample/Sample.java",
                source.lines().toList(),
                "member-evidence",
                sink
        );

        assertEquals(
                1,
                sink.toExtractedFacts()
                        .relations()
                        .accessesField()
                        .size()
        );

        RelationFact relation = sink.toExtractedFacts()
                .relations()
                .accessesField()
                .get(0);

        EvidenceFact evidence = evidenceOf(
                sink,
                relation.evidenceIds().get(0)
        );

        assertEquals("System.out", evidence.snippet());
        assertEquals(
                "expression",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                "field_access",
                evidence.attrs().get("role")
        );
        assertNotNull(relation.callSiteLine());
    }

    @Test
    @DisplayName("ANNOTATED_WITH 관계가 어노테이션 범위 Evidence를 참조한다")
    void annotatedWithUsesAnnotationEvidence() throws Exception {
        String source = """
                @Deprecated
                class Sample {
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        AnnotationExpr annotation = unit
                .findFirst(AnnotationExpr.class)
                .orElseThrow();

        TypeRef annotationRef = TypeRef.builder()
                .raw("java.lang.Deprecated")
                .sourceText("Deprecated")
                .unresolved(Boolean.FALSE)
                .build();

        ExtractionSink sink = new ExtractionSink();

        invoke(
                "addAnnotationRelations",
                new Class<?>[]{
                        String.class,
                        List.class,
                        List.class,
                        String.class,
                        List.class,
                        String.class,
                        ExtractionSink.class
                },
                "type:sample.Sample",
                List.of(annotation),
                List.of(annotationRef),
                "src/main/java/sample/Sample.java",
                source.lines().toList(),
                "type-evidence",
                sink
        );

        RelationFact relation = sink.toExtractedFacts()
                .relations()
                .annotatedWith()
                .get(0);

        EvidenceFact evidence = evidenceOf(
                sink,
                relation.evidenceIds().get(0)
        );

        assertEquals("@Deprecated", evidence.snippet());
        assertEquals(
                "annotation",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                "annotation",
                evidence.attrs().get("role")
        );
        assertTrue(
                relation.dstSymbol()
                        .endsWith("java.lang.Deprecated")
        );
    }

    private EvidenceFact evidenceOf(
            ExtractionSink sink,
            String evidenceId
    ) {
        EvidenceFact evidence = sink.toExtractedFacts()
                .evidence()
                .get(evidenceId);

        assertNotNull(evidence);
        assertNotNull(evidence.attrs());
        return evidence;
    }

    private Object invoke(
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = JavaParserAstFactsExtractor.class
                .getDeclaredMethod(
                        methodName,
                        parameterTypes
                );
        method.setAccessible(true);
        return method.invoke(extractor, arguments);
    }
}

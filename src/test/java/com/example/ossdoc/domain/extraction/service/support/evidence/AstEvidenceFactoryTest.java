package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstEvidenceFactoryTest {

    @Test
    @DisplayName("표현식 Evidence에 정확한 span과 granularity·role을 기록한다")
    void createsExpressionEvidence() {
        String source = """
                class Sample {
                    String call() {
                        return target.name();
                    }
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class)
                .orElseThrow();

        EvidenceFact evidence = AstEvidenceFactory.create(
                "src/main/java/sample/Sample.java",
                source.lines().toList(),
                call,
                "method:sample.Sample#call()",
                EvidenceType.AST,
                EvidenceGranularity.EXPRESSION,
                "method_call"
        );

        assertNotNull(evidence.id());
        assertTrue(!evidence.id().isBlank());
        assertEquals(EvidenceType.AST, evidence.type());
        assertEquals("target.name()", evidence.snippet());
        assertNotNull(evidence.startLine());
        assertNotNull(evidence.startCol());
        assertNotNull(evidence.endLine());
        assertNotNull(evidence.endCol());
        assertEquals(
                "expression",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                "method_call",
                evidence.attrs().get("role")
        );
        assertNotNull(evidence.hash());
    }

    @Test
    @DisplayName("같은 노드와 소유 심볼은 안정적인 Evidence ID를 생성한다")
    void createsStableEvidenceId() {
        String source = """
                class Sample {
                    void call() {
                        service.publish(event);
                    }
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class)
                .orElseThrow();

        EvidenceFact first = AstEvidenceFactory.create(
                "src/main/java/sample/Sample.java",
                source.lines().toList(),
                call,
                "method:sample.Sample#call()",
                EvidenceType.AST,
                EvidenceGranularity.EXPRESSION,
                "event_publication"
        );

        EvidenceFact second = AstEvidenceFactory.create(
                "src/main/java/sample/Sample.java",
                source.lines().toList(),
                call,
                "method:sample.Sample#call()",
                EvidenceType.AST,
                EvidenceGranularity.EXPRESSION,
                "event_publication"
        );

        assertEquals(first.id(), second.id());
    }
}

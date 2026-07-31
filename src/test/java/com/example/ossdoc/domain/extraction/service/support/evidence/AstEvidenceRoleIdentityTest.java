package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AstEvidenceRoleIdentityTest {

    @Test
    @DisplayName("같은 AST 노드라도 사실 역할이 다르면 Evidence ID를 분리한다")
    void separatesEvidenceIdentityByRole() {
        AnnotationExpr annotation =
                StaticJavaParser.parseAnnotation("@GetMapping(\"/users\")");

        EvidenceFact relationEvidence = AstEvidenceFactory.create(
                "src/main/java/sample/UserController.java",
                List.of(),
                annotation,
                "method:sample.UserController#users()",
                EvidenceType.AST,
                EvidenceGranularity.ANNOTATION,
                "annotation"
        );

        EvidenceFact endpointEvidence = AstEvidenceFactory.create(
                "src/main/java/sample/UserController.java",
                List.of(),
                annotation,
                "method:sample.UserController#users()",
                EvidenceType.AST,
                EvidenceGranularity.ANNOTATION,
                "endpoint_mapping"
        );

        assertNotEquals(relationEvidence.id(), endpointEvidence.id());
        assertEquals(relationEvidence.symbol(), endpointEvidence.symbol());
        assertEquals("annotation", relationEvidence.attrs().get("role"));
        assertEquals("endpoint_mapping", endpointEvidence.attrs().get("role"));
    }
}

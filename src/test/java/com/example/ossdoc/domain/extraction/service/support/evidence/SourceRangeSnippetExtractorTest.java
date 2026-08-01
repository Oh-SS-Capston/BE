package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SourceRangeSnippetExtractorTest {

    @Test
    @DisplayName("같은 행에 다른 코드가 있어도 호출 표현식 범위만 추출한다")
    void extractsOnlyExpressionColumns() {
        String source = """
                class Sample {
                    String call() {
                        Target target = new Target(); return target.name();
                    }
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        MethodCallExpr targetCall = unit.findAll(MethodCallExpr.class)
                .stream()
                .filter(call -> call.getNameAsString().equals("name"))
                .findFirst()
                .orElseThrow();

        String snippet = SourceRangeSnippetExtractor.extract(
                source.lines().toList(),
                targetCall
        );

        assertEquals("target.name()", snippet);
        assertFalse(snippet.contains("new Target()"));
        assertFalse(snippet.contains("return "));
    }

    @Test
    @DisplayName("여러 행으로 구성된 호출 표현식의 시작·종료 column을 반영한다")
    void extractsMultilineExpression() {
        String source = """
                class Sample {
                    void call() {
                        service.publish(
                                new OrderCreatedEvent(
                                        orderId
                                )
                        );
                    }
                }
                """;

        CompilationUnit unit = StaticJavaParser.parse(source);
        MethodCallExpr publishCall = unit.findAll(MethodCallExpr.class)
                .stream()
                .filter(call -> call.getNameAsString().equals("publish"))
                .findFirst()
                .orElseThrow();

        String snippet = SourceRangeSnippetExtractor.extract(
                source.lines().toList(),
                publishCall
        );

        assertNotNull(snippet);

        List<String> snippetLines = snippet.lines().toList();

        assertEquals(5, snippetLines.size());
        assertEquals("service.publish(", snippetLines.get(0));
        assertEquals(
                "                new OrderCreatedEvent(",
                snippetLines.get(1)
        );
        assertEquals(
                "                        orderId",
                snippetLines.get(2)
        );
        assertEquals(
                "                )",
                snippetLines.get(3)
        );
        assertEquals(
                "        )",
                snippetLines.get(4)
        );

        assertFalse(snippet.endsWith(";"));
    }

    @Test
    @DisplayName("소스 행을 사용할 수 없으면 노드 문자열로 안전하게 대체한다")
    void fallsBackToNodeText() {
        MethodCallExpr call = StaticJavaParser.parseExpression(
                "target.name()"
        ).asMethodCallExpr();

        String snippet = SourceRangeSnippetExtractor.extract(
                List.of(),
                call
        );

        assertEquals("target.name()", snippet);
    }
}

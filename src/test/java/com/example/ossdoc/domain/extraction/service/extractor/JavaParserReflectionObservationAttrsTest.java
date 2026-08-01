package com.example.ossdoc.domain.extraction.service.extractor;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserReflectionObservationAttrsTest {

    @Test
    @DisplayName("Class.forName 문자열을 reflection target type으로 기록한다")
    void extractsForNameTarget() throws Exception {
        Map<String, Object> attrs = attrs(
                "Class.forName(\"sample.Target\")"
        );

        assertEquals("type", attrs.get("reflection_kind"));
        assertEquals("sample.Target", attrs.get("target_type"));
        assertEquals("static", attrs.get("target_resolution"));
    }

    @Test
    @DisplayName("getDeclaredMethod에서 owner type, member name, parameter type을 기록한다")
    void extractsMethodTarget() throws Exception {
        Map<String, Object> attrs = attrs(
                "sample.Target.class.getDeclaredMethod(\"run\", String.class)"
        );

        assertEquals("method", attrs.get("reflection_kind"));
        assertEquals("run", attrs.get("member_name"));
        assertTrue(String.valueOf(attrs.get("target_type")).endsWith("Target"));
        @SuppressWarnings("unchecked")
        List<String> parameterTypes =
                (List<String>) attrs.get("parameter_types");
        assertEquals(1, parameterTypes.size());
        assertTrue(parameterTypes.get(0).endsWith("String"));
        assertEquals(Boolean.TRUE, attrs.get("declared_only"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attrs(String expression) throws Exception {
        JavaParserAstFactsExtractor extractor =
                new JavaParserAstFactsExtractor();
        Method method = JavaParserAstFactsExtractor.class
                .getDeclaredMethod(
                        "reflectionObservationAttrs",
                        MethodCallExpr.class
                );
        method.setAccessible(true);
        MethodCallExpr call = StaticJavaParser
                .parseExpression(expression)
                .asMethodCallExpr();
        return (Map<String, Object>) method.invoke(extractor, call);
    }
}

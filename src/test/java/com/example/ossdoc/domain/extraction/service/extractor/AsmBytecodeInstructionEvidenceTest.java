package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsmBytecodeInstructionEvidenceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ASM CALLS·ACCESSES_FIELD·REFLECTION_SITE가 개별 instruction Evidence를 참조한다")
    void relationsAndReflectionUseInstructionEvidence()
            throws Exception {
        Path classes = compileFixture();
        Path classFile = classes.resolve(
                "sample/BytecodeFixture.class"
        );

        ExtractionContext context =
                mock(ExtractionContext.class);

        when(context.repoRoot()).thenReturn(tempDir);
        when(context.module()).thenReturn("sample-app");
        when(context.bytecodeRootString()).thenReturn(
                classes.toString()
        );
        when(context.includeObservations()).thenReturn(true);

        ExtractionSink sink = new ExtractionSink();

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
                classFile,
                sink
        );

        var facts = sink.toExtractedFacts();
        Map<String, EvidenceFact> evidence = facts.evidence();

        RelationFact targetCall =
                facts.relations().calls()
                        .stream()
                        .filter(relation ->
                                relation.dstSymbol() != null
                                        && relation.dstSymbol()
                                        .contains(
                                                "sample.Target#call"
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        EvidenceFact callEvidence =
                evidence.get(
                        targetCall.evidenceIds().get(0)
                );

        assertInstructionEvidence(
                callEvidence,
                "method_call",
                "INVOKEVIRTUAL",
                "call"
        );
        assertNotNull(targetCall.callSiteLine());

        RelationFact fieldAccess =
                facts.relations().accessesField()
                        .stream()
                        .filter(relation ->
                                relation.dstSymbol() != null
                                        && relation.dstSymbol()
                                        .contains(
                                                "BytecodeFixture#value"
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        EvidenceFact fieldEvidence =
                evidence.get(
                        fieldAccess.evidenceIds().get(0)
                );

        assertInstructionEvidence(
                fieldEvidence,
                "field_access",
                "PUTFIELD",
                "value"
        );

        ObservationFact reflection =
                facts.observations()
                        .reflectionSites()
                        .stream()
                        .filter(observation ->
                                "forName".equals(
                                        observation.attrs()
                                                .get("method")
                                )
                        )
                        .findFirst()
                        .orElseThrow();

        EvidenceFact reflectionEvidence =
                evidence.get(
                        reflection.evidenceIds().get(0)
                );

        assertInstructionEvidence(
                reflectionEvidence,
                "reflection_call",
                "INVOKESTATIC",
                "forName"
        );

        RelationFact reflectionCall =
                facts.relations().calls()
                        .stream()
                        .filter(relation ->
                                relation.dstSymbol() != null
                                        && relation.dstSymbol()
                                        .contains(
                                                "java.lang.Class#forName"
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        EvidenceFact reflectionCallEvidence =
                evidence.get(
                        reflectionCall.evidenceIds().get(0)
                );

        assertInstructionEvidence(
                reflectionCallEvidence,
                "method_call",
                "INVOKESTATIC",
                "forName"
        );

        assertNotEquals(
                reflectionCallEvidence.id(),
                reflectionEvidence.id(),
                "같은 instruction이라도 관계와 Observation 역할별 ID가 달라야 함"
        );

        assertEquals(
                reflectionCallEvidence.attrs()
                        .get("instruction_index"),
                reflectionEvidence.attrs()
                        .get("instruction_index")
        );

        assertEquals(
                reflectionCallEvidence.startLine(),
                reflectionEvidence.startLine()
        );

        assertNotEquals(
                callEvidence.attrs().get("instruction_index"),
                reflectionEvidence.attrs()
                        .get("instruction_index")
        );

        assertFalse(
                callEvidence.id().equals(
                        targetCall.srcSymbol()
                )
        );
    }

    private void assertInstructionEvidence(
            EvidenceFact evidence,
            String expectedRole,
            String expectedOpcode,
            String expectedMember
    ) {
        assertNotNull(evidence);
        assertEquals(
                "instruction",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                expectedRole,
                evidence.attrs().get("role")
        );
        assertEquals(
                expectedOpcode,
                evidence.attrs().get("opcode_name")
        );
        assertEquals(
                expectedMember,
                evidence.attrs().get("member_name")
        );
        assertNotNull(
                evidence.attrs().get("instruction_index")
        );
        assertNotNull(evidence.startLine());
        assertNotNull(evidence.endLine());
        assertTrue(
                evidence.snippet().startsWith(
                        expectedOpcode
                )
        );
        assertNotNull(evidence.hash());
    }

    private Path compileFixture() throws Exception {
        Path sourceRoot = tempDir.resolve("src");
        Path classes = tempDir.resolve("classes");
        Path sourceFile = sourceRoot.resolve(
                "sample/BytecodeFixture.java"
        );

        Files.createDirectories(
                sourceFile.getParent()
        );
        Files.createDirectories(classes);

        String source = """
                package sample;

                public class BytecodeFixture {
                    private String value;

                    public void run(Target target) throws Exception {
                        this.value = target.call(); Class.forName("sample.Target");
                    }
                }

                class Target {
                    String call() {
                        return "ok";
                    }
                }
                """;

        Files.writeString(
                sourceFile,
                source,
                StandardCharsets.UTF_8
        );

        JavaCompiler compiler =
                ToolProvider.getSystemJavaCompiler();

        assertNotNull(
                compiler,
                "JDK JavaCompiler가 필요함"
        );

        int exitCode = compiler.run(
                null,
                null,
                null,
                "-g",
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString(),
                sourceFile.toString()
        );

        assertEquals(
                0,
                exitCode,
                "테스트 fixture 컴파일 실패"
        );

        return classes;
    }
}

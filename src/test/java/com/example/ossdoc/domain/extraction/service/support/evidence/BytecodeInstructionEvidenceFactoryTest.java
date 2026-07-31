package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BytecodeInstructionEvidenceFactoryTest {

    @Test
    @DisplayName("method instruction의 opcode·index·source line을 Evidence에 기록한다")
    void createsMethodInstructionEvidence() {
        EvidenceFact evidence =
                BytecodeInstructionEvidenceFactory.create(
                        "build/classes/java/main/sample/Sample.class",
                        "sample-app",
                        "method:sample.Sample#run()",
                        27,
                        6,
                        Opcodes.INVOKEVIRTUAL,
                        "method_call",
                        "sample/Target",
                        "call",
                        "()Ljava/lang/String;",
                        false
                );

        assertNotNull(evidence.id());
        assertEquals(
                EvidenceType.BYTECODE,
                evidence.type()
        );
        assertEquals(27, evidence.startLine());
        assertEquals(27, evidence.endLine());
        assertEquals(
                "method:sample.Sample#run()",
                evidence.symbol()
        );
        assertEquals(
                "INVOKEVIRTUAL sample.Target.call()Ljava/lang/String;",
                evidence.snippet()
        );
        assertEquals(
                "instruction",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                "method_call",
                evidence.attrs().get("role")
        );
        assertEquals(
                6,
                evidence.attrs().get("instruction_index")
        );
        assertEquals(
                "INVOKEVIRTUAL",
                evidence.attrs().get("opcode_name")
        );
        assertEquals(
                "sample.Target",
                evidence.attrs().get("target_owner")
        );
        assertEquals(
                "sample-app",
                evidence.attrs().get("module")
        );
    }

    @Test
    @DisplayName("같은 instruction도 role이 다르면 Evidence ID가 분리된다")
    void roleSeparatesEvidenceIdentity() {
        EvidenceFact call =
                create("method_call", 4);

        EvidenceFact reflection =
                create("reflection_call", 4);

        EvidenceFact nextCall =
                create("method_call", 5);

        assertNotEquals(call.id(), reflection.id());
        assertNotEquals(call.id(), nextCall.id());
        assertEquals(
                call.attrs().get("instruction_index"),
                reflection.attrs().get("instruction_index")
        );
    }

    @Test
    @DisplayName("같은 입력은 안정적인 Evidence ID를 생성한다")
    void createsStableId() {
        assertEquals(
                create("method_call", 4).id(),
                create("method_call", 4).id()
        );
    }

    private EvidenceFact create(
            String role,
            int instructionIndex
    ) {
        return BytecodeInstructionEvidenceFactory.create(
                "build/classes/java/main/sample/Sample.class",
                "sample-app",
                "method:sample.Sample#run()",
                12,
                instructionIndex,
                Opcodes.INVOKESTATIC,
                role,
                "java/lang/Class",
                "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;",
                false
        );
    }
}

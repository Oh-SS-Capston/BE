package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.service.support.util.EvidenceIdGenerator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ASM MethodVisitor가 확인한 개별 bytecode instruction을
 * EvidenceFact로 변환한다.
 *
 * instruction_index는 실제 byte offset이 아니라 메서드 내부에서
 * ASM instruction callback이 호출된 0-based 순서다.
 */
public final class BytecodeInstructionEvidenceFactory {

    private BytecodeInstructionEvidenceFactory() {
    }

    public static EvidenceFact create(
            String relativePath,
            String module,
            String ownerSymbol,
            Integer sourceLine,
            int instructionIndex,
            int opcode,
            String role,
            String targetOwner,
            String memberName,
            String descriptor,
            Boolean interfaceCall
    ) {
        String normalizedOwner =
                normalizeOwner(targetOwner);

        String opcodeName = opcodeName(opcode);

        String snippet = instructionSnippet(
                opcodeName,
                normalizedOwner,
                memberName,
                descriptor
        );

        Map<String, Object> attrs =
                new LinkedHashMap<>();

        attrs.put(
                "granularity",
                EvidenceGranularity.INSTRUCTION.code()
        );
        attrs.put("role", normalizeRole(role));
        attrs.put("instruction_index", instructionIndex);
        attrs.put("opcode", opcode);
        attrs.put("opcode_name", opcodeName);

        if (normalizedOwner != null) {
            attrs.put("target_owner", normalizedOwner);
        }

        if (memberName != null && !memberName.isBlank()) {
            attrs.put("member_name", memberName);
        }

        if (descriptor != null && !descriptor.isBlank()) {
            attrs.put("descriptor", descriptor);
        }

        if (interfaceCall != null) {
            attrs.put("interface_call", interfaceCall);
        }

        if (sourceLine != null) {
            attrs.put("source_line", sourceLine);
        }

        if (module != null && !module.isBlank()) {
            attrs.put("module", module);
        }

        attrs.put("class_file", true);

        String evidenceIdentity = String.join(
                "|",
                safe(ownerSymbol),
                normalizeRole(role),
                String.valueOf(instructionIndex),
                String.valueOf(opcode),
                safe(normalizedOwner),
                safe(memberName),
                safe(descriptor)
        );

        String evidenceId =
                EvidenceIdGenerator.generate(
                        EvidenceType.BYTECODE,
                        relativePath,
                        sourceLine,
                        null,
                        sourceLine,
                        null,
                        evidenceIdentity
                );

        return EvidenceFact.builder()
                .id(evidenceId)
                .type(EvidenceType.BYTECODE)
                .path(relativePath)
                .startLine(sourceLine)
                .endLine(sourceLine)
                .symbol(ownerSymbol)
                .snippet(snippet)
                .hash(
                        snippet == null || snippet.isBlank()
                                ? null
                                : Integer.toHexString(
                                        snippet.hashCode()
                                )
                )
                .attrs(Map.copyOf(attrs))
                .build();
    }

    private static String instructionSnippet(
            String opcodeName,
            String owner,
            String memberName,
            String descriptor
    ) {
        StringBuilder snippet =
                new StringBuilder(opcodeName);

        if (owner != null && !owner.isBlank()) {
            snippet.append(' ')
                    .append(owner);
        }

        if (memberName != null
                && !memberName.isBlank()) {
            if (owner != null && !owner.isBlank()) {
                snippet.append('.');
            } else {
                snippet.append(' ');
            }

            snippet.append(memberName);
        }

        if (descriptor != null
                && !descriptor.isBlank()) {
            snippet.append(descriptor);
        }

        return snippet.toString();
    }

    private static String normalizeOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }

        return owner.replace('/', '.');
    }

    private static String normalizeRole(String role) {
        return role == null || role.isBlank()
                ? "bytecode_instruction"
                : role.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case 178 -> "GETSTATIC";
            case 179 -> "PUTSTATIC";
            case 180 -> "GETFIELD";
            case 181 -> "PUTFIELD";
            case 182 -> "INVOKEVIRTUAL";
            case 183 -> "INVOKESPECIAL";
            case 184 -> "INVOKESTATIC";
            case 185 -> "INVOKEINTERFACE";
            case 186 -> "INVOKEDYNAMIC";
            default -> "OPCODE_" + opcode;
        };
    }
}

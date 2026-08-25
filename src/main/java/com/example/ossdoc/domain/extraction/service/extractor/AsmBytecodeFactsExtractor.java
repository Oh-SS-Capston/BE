package com.example.ossdoc.domain.extraction.service.extractor;

import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ParamFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.SignatureFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.AccessLevel;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.Modifier;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.ResolutionStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;
import com.example.ossdoc.domain.extraction.enums.SymbolOriginKind;
import com.example.ossdoc.domain.extraction.enums.TypeKind;
import com.example.ossdoc.domain.extraction.service.support.evidence.BytecodeAnnotationEvidenceFactory;
import com.example.ossdoc.domain.extraction.service.support.evidence.BytecodeInstructionEvidenceFactory;
import com.example.ossdoc.domain.extraction.service.support.util.ConfidenceHints;
import com.example.ossdoc.domain.extraction.service.support.util.EvidenceIdGenerator;
import com.example.ossdoc.domain.extraction.service.support.util.RelationResolutionFactory;
import com.example.ossdoc.domain.extraction.service.support.util.RepoPathUtils;
import com.example.ossdoc.domain.extraction.service.support.util.SymbolIdFactory;
import com.example.ossdoc.domain.extraction.service.support.util.TypeRefFactory;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * ASM 기반 bytecode extractor.
 *
 * 새 구조에서는 planner가 확정한 class 파일 목록만 처리한다.
 */
@Component
public class AsmBytecodeFactsExtractor implements FactsExtractor {

    private static final int ASM_API = Opcodes.ASM9;

    /**
     * 컴파일러가 자동으로 붙이는, 분석 가치가 없는 어노테이션.
     *
     * @kotlin.Metadata는 Kotlin 컴파일러가 모든 클래스에 삽입하며
     * d1/d2 필드에 리플렉션용 메타데이터를 인코딩한 문자열로 담는다.
     * 사람이 읽을 수 없는 바이너리라 Semantic Graph에 기여하지 못하면서
     * evidence 수와 facts.json 크기만 키운다.
     * (JUnit 분석에서 이 한 어노테이션이 snippet에 NUL 156개를 유입시켰다.)
     *
     * 값 자체의 NUL은 BytecodeAnnotationEvidenceFactory가 별도로 막는다.
     * 여기서는 "애초에 수집하지 않는다"는 노이즈 제거가 목적이다.
     */
    private static final Set<String> COMPILER_GENERATED_ANNOTATIONS = Set.of(
            "kotlin.Metadata"
    );

    private static final class AnnotationData {
        private final String descriptor;
        private final String qualifiedName;
        private final boolean runtimeVisible;
        private final int annotationIndex;
        private final String ownerSymbol;
        private final String relativePath;
        private final String module;
        private final Map<String, Object> values =
                new LinkedHashMap<>();

        private String relationEvidenceId;

        private AnnotationData(
                String descriptor,
                String qualifiedName,
                boolean runtimeVisible,
                int annotationIndex,
                String ownerSymbol,
                String relativePath,
                String module
        ) {
            this.descriptor = descriptor;
            this.qualifiedName = qualifiedName;
            this.runtimeVisible = runtimeVisible;
            this.annotationIndex = annotationIndex;
            this.ownerSymbol = ownerSymbol;
            this.relativePath = relativePath;
            this.module = module;
        }
    }

    @Override
    public ChunkKind supports() {
        return ChunkKind.ASM;
    }

    @Override
    public ChunkResult extract(ExtractionContext context) {
        ExtractionSink sink = new ExtractionSink();

        if (!context.isAsmChunk()) {
            sink.addError("ASM extractor received non-ASM chunk: " + context.chunkKind());
            return sink.toChunkResult(context.chunk());
        }

        if (!context.hasFiles()) {
            sink.addWarning("ASM chunk contains no files: " + context.chunkId());
            return sink.toChunkResult(context.chunk());
        }

        for (Path classFile : context.files()) {
            if (classFile == null || !Files.isRegularFile(classFile)) {
                // Files.isRegularFile은 존재하지 않는 파일도 false로 처리하므로
                // exists + isRegularFile 중복 stat 호출 없이 기존 skip 정책을 유지한다.
                sink.addWarning("class file does not exist or is not a regular file: " + classFile);
                sink.recordFileSkipped();
                continue;
            }
            if (!classFile.toString().endsWith(".class")) {
                sink.recordFileSkipped();
                continue;
            }
            visitClassFile(context, classFile, sink);
        }

        return sink.toChunkResult(context.chunk());
    }

    private void visitClassFile(
            ExtractionContext context,
            Path classFile,
            ExtractionSink sink
    ) {
        String relativePath = RepoPathUtils.toRepoRelative(context.repoRoot(), classFile);
        sink.recordFileScanned();

        try {
            ClassReader classReader = new ClassReader(Files.readAllBytes(classFile));
            classReader.accept(
                    new AsmFactsVisitor(context, classFile, relativePath, sink),
                    ClassReader.SKIP_FRAMES
            );
            sink.recordFileParsed();
        } catch (Exception e) {
            sink.addError("failed to read class file: " + relativePath + " (" + e.getMessage() + ")");
        }
    }

    private final class AsmFactsVisitor extends ClassVisitor {

        private final ExtractionContext context;
        private final Path classFile;
        private final String relativePath;
        private final ExtractionSink sink;
        private final String moduleSymbol;
        private final List<AnnotationData> typeAnnotations = new ArrayList<>();
        private int nextTypeAnnotationIndex;

        private String packageName;
        private String packageSymbol;
        private String typeQualifiedName;
        private String typeSymbol;
        private String typeEvidenceId;

        private AsmFactsVisitor(
                ExtractionContext context,
                Path classFile,
                String relativePath,
                ExtractionSink sink
        ) {
            super(ASM_API);
            this.context = context;
            this.classFile = classFile;
            this.relativePath = relativePath;
            this.sink = sink;
            this.moduleSymbol = ensureModuleSymbol(context, sink);
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces
        ) {
            this.typeQualifiedName = internalNameToQualified(name);
            this.typeSymbol = SymbolIdFactory.type(typeQualifiedName);
            this.packageName = packageName(typeQualifiedName);
            this.packageSymbol = ensurePackageSymbol(
                    context,
                    moduleSymbol,
                    packageName,
                    relativePath,
                    sink
            );

            EvidenceFact typeEvidence = buildBytecodeEvidence(typeSymbol);
            this.typeEvidenceId = typeEvidence.id();
            sink.addEvidence(typeEvidence);
            sink.addSymbol(SymbolFact.builder()
                    .symbol(typeSymbol)
                    .kind(SymbolKind.TYPE)
                    .typeKind(typeKind(access))
                    .name(simpleName(typeQualifiedName))
                    .qualifiedName(typeQualifiedName)
                    .packageSymbol(packageSymbol)
                    .module(context.module())
                    .bytecodeRoot(context.bytecodeRootString())
                    .access(accessLevel(access))
                    .modifiers(modifierKinds(access))
                    .origin(SymbolOriginKind.BYTECODE)
                    .annotations(List.of())
                    .evidenceIds(List.of(typeEvidence.id()))
                    .sourceFile(relativePath)
                    .build());
        }

        @Override
        public AnnotationVisitor visitAnnotation(
                String descriptor,
                boolean visible
        ) {
            String annotationName = annotationQualifiedName(descriptor);

            // null 반환 = 이 어노테이션의 값 방문을 건너뛴다(ASM 규약).
            if (isCompilerGeneratedAnnotation(annotationName)) {
                return null;
            }

            AnnotationData annotation =
                    new AnnotationData(
                            descriptor,
                            annotationName,
                            visible,
                            nextTypeAnnotationIndex++,
                            typeSymbol,
                            relativePath,
                            context.module()
                    );

            typeAnnotations.add(annotation);

            return annotationVisitor(
                    annotation.values,
                    () -> registerAnnotationRelationEvidence(
                            annotation,
                            sink
                    )
            );
        }

        @Override
        public FieldVisitor visitField(
                int access,
                String name,
                String descriptor,
                String signature,
                Object value
        ) {
            String fieldSymbol = SymbolIdFactory.field(typeQualifiedName, name);
            EvidenceFact evidence = buildBytecodeEvidence(fieldSymbol);
            sink.addEvidence(evidence);

            TypeRef fieldTypeRef = typeRefFromAsmType(Type.getType(descriptor));
            sink.addSymbol(SymbolFact.builder()
                    .symbol(fieldSymbol)
                    .kind(SymbolKind.FIELD)
                    .name(name)
                    .ownerSymbol(typeSymbol)
                    .module(context.module())
                    .bytecodeRoot(context.bytecodeRootString())
                    .access(accessLevel(access))
                    .modifiers(modifierKinds(access))
                    .origin(SymbolOriginKind.BYTECODE)
                    .evidenceIds(List.of(evidence.id()))
                    .signature(SignatureFact.builder().fieldType(fieldTypeRef).build())
                    .sourceFile(relativePath)
                    .build());

            return new FieldVisitor(ASM_API) {

                private int nextFieldAnnotationIndex;

                @Override
                public AnnotationVisitor visitAnnotation(
                        String annotationDescriptor,
                        boolean visible
                ) {
                    String annotationName =
                            annotationQualifiedName(annotationDescriptor);

                    // null 반환 = 이 어노테이션의 값 방문을 건너뛴다(ASM 규약).
                    if (isCompilerGeneratedAnnotation(annotationName)) {
                        return null;
                    }

                    AnnotationData annotation =
                            new AnnotationData(
                                    annotationDescriptor,
                                    annotationName,
                                    visible,
                                    nextFieldAnnotationIndex++,
                                    fieldSymbol,
                                    relativePath,
                                    context.module()
                            );

                    return annotationVisitor(
                            annotation.values,
                            () -> {
                                registerAnnotationRelationEvidence(
                                        annotation,
                                        sink
                                );

                                if (context.includeObservations()
                                        && isInjectionAnnotationName(
                                        annotation.qualifiedName
                                )) {
                                    EvidenceFact injectionEvidence =
                                            registerSemanticAnnotationEvidence(
                                                    annotation,
                                                    "injection_annotation",
                                                    sink
                                            );

                                    sink.addObservation(
                                            ObservationFact.builder()
                                                    .kind(
                                                            ObservationKind
                                                                    .DI_INJECTION_SITE
                                                    )
                                                    .siteSymbol(fieldSymbol)
                                                    .targetTypeRef(
                                                            fieldTypeRef
                                                    )
                                                    .evidenceIds(
                                                            List.of(
                                                                    injectionEvidence
                                                                            .id()
                                                            )
                                                    )
                                                    .origin(
                                                            FactOriginKind
                                                                    .OBSERVED
                                                    )
                                                    .confidenceHint(
                                                            ConfidenceHints
                                                                    .observation(
                                                                            List.of(
                                                                                    EvidenceType
                                                                                            .BYTECODE
                                                                            )
                                                                    )
                                                    )
                                                    .note(
                                                            "field injection from bytecode annotation"
                                                    )
                                                    .attrs(Map.of(
                                                            "annotation",
                                                            annotation
                                                                    .qualifiedName
                                                    ))
                                                    .build()
                                    );
                                }
                            }
                    );
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
        ) {
            boolean constructor = "<init>".equals(name);
            SignatureFact methodSignature = methodSignature(descriptor, exceptions);
            String symbol = constructor
                    ? SymbolIdFactory.constructor(typeQualifiedName, methodSignature)
                    : SymbolIdFactory.method(typeQualifiedName, name, methodSignature);

            EvidenceFact evidence = buildBytecodeEvidence(symbol);
            sink.addEvidence(evidence);
            sink.addSymbol(SymbolFact.builder()
                    .symbol(symbol)
                    .kind(constructor ? SymbolKind.CONSTRUCTOR : SymbolKind.METHOD)
                    .name(constructor ? simpleName(typeQualifiedName) : name)
                    .ownerSymbol(typeSymbol)
                    .module(context.module())
                    .bytecodeRoot(context.bytecodeRootString())
                    .access(accessLevel(access))
                    .modifiers(modifierKinds(access))
                    .origin(SymbolOriginKind.BYTECODE)
                    .evidenceIds(List.of(evidence.id()))
                    .signature(methodSignature)
                    .sourceFile(relativePath)
                    .build());

            return new MethodVisitor(ASM_API) {

                private final List<AnnotationData> methodAnnotations =
                        new ArrayList<>();
                private int nextMethodAnnotationIndex;

                /**
                 * 실제 bytecode offset이 아니라 MethodVisitor에 전달되는
                 * instruction callback의 0-based 순서다.
                 */
                private int nextInstructionIndex;

                /**
                 * LineNumberTable에서 가장 최근에 확인된 소스 행.
                 * 디버그 정보가 없는 class 파일에서는 null이다.
                 */
                private Integer currentSourceLine;

                @Override
                public AnnotationVisitor visitAnnotation(
                        String annotationDescriptor,
                        boolean visible
                ) {
                    String annotationName =
                            annotationQualifiedName(annotationDescriptor);

                    // null 반환 = 이 어노테이션의 값 방문을 건너뛴다(ASM 규약).
                    if (isCompilerGeneratedAnnotation(annotationName)) {
                        return null;
                    }

                    AnnotationData annotation =
                            new AnnotationData(
                                    annotationDescriptor,
                                    annotationName,
                                    visible,
                                    nextMethodAnnotationIndex++,
                                    symbol,
                                    relativePath,
                                    context.module()
                            );

                    methodAnnotations.add(annotation);

                    return annotationVisitor(
                            annotation.values,
                            () -> registerAnnotationRelationEvidence(
                                    annotation,
                                    sink
                            )
                    );
                }

                @Override
                public void visitLineNumber(
                        int line,
                        Label start
                ) {
                    currentSourceLine = line;
                    super.visitLineNumber(line, start);
                }

                @Override
                public void visitMethodInsn(
                        int opcode,
                        String owner,
                        String methodName,
                        String methodDescriptor,
                        boolean isInterface
                ) {
                    int instructionIndex =
                            claimInstructionIndex();

                    EvidenceFact callEvidence =
                            buildInstructionEvidence(
                                    symbol,
                                    currentSourceLine,
                                    instructionIndex,
                                    opcode,
                                    "method_call",
                                    owner,
                                    methodName,
                                    methodDescriptor,
                                    isInterface
                            );
                    sink.addEvidence(callEvidence);

                    Type methodType =
                            Type.getMethodType(methodDescriptor);

                    SignatureFact calleeSignature =
                            SignatureFact.builder()
                                    .params(
                                            Arrays.stream(
                                                            methodType
                                                                    .getArgumentTypes()
                                                    )
                                                    .map(type ->
                                                            ParamFact.builder()
                                                                    .name(null)
                                                                    .typeRef(
                                                                            typeRefFromAsmType(
                                                                                    type
                                                                            )
                                                                    )
                                                                    .build()
                                                    )
                                                    .toList()
                                    )
                                    .build();

                    String dstSymbol =
                            "<init>".equals(methodName)
                                    ? SymbolIdFactory.constructor(
                                            internalNameToQualified(owner),
                                            calleeSignature
                                    )
                                    : SymbolIdFactory.method(
                                            internalNameToQualified(owner),
                                            methodName,
                                            calleeSignature
                                    );

                    sink.addRelation(RelationFact.builder()
                            .kind(RelationKind.CALLS)
                            .srcSymbol(symbol)
                            .dstSymbol(dstSymbol)
                            .evidenceIds(
                                    List.of(callEvidence.id())
                            )
                            .resolution(
                                    RelationResolutionFactory.resolved()
                            )
                            .origin(FactOriginKind.BYTECODE)
                            .callSiteLine(currentSourceLine)
                            .confidenceHint(
                                    ConfidenceHints.relation(
                                            ResolutionStatus.RESOLVED,
                                            FactOriginKind.BYTECODE
                                    )
                            )
                            .build());

                    if (context.includeObservations()
                            && isReflectionOwner(
                                    owner,
                                    methodName
                            )) {
                        EvidenceFact reflectionEvidence =
                                buildInstructionEvidence(
                                        symbol,
                                        currentSourceLine,
                                        instructionIndex,
                                        opcode,
                                        "reflection_call",
                                        owner,
                                        methodName,
                                        methodDescriptor,
                                        isInterface
                                );
                        sink.addEvidence(reflectionEvidence);

                        sink.addObservation(
                                ObservationFact.builder()
                                        .kind(
                                                ObservationKind.REFLECTION_SITE
                                        )
                                        .siteSymbol(symbol)
                                        .evidenceIds(
                                                List.of(
                                                        reflectionEvidence.id()
                                                )
                                        )
                                        .origin(
                                                FactOriginKind.OBSERVED
                                        )
                                        .confidenceHint(
                                                ConfidenceHints.observation(
                                                        List.of(
                                                                EvidenceType.BYTECODE
                                                        )
                                                )
                                        )
                                        .note(
                                                "reflection API usage from bytecode"
                                        )
                                        .attrs(Map.of(
                                                "owner",
                                                internalNameToQualified(owner),
                                                "method",
                                                methodName,
                                                "descriptor",
                                                methodDescriptor
                                        ))
                                        .build()
                        );
                    }

                    super.visitMethodInsn(
                            opcode,
                            owner,
                            methodName,
                            methodDescriptor,
                            isInterface
                    );
                }

                @Override
                public void visitFieldInsn(
                        int opcode,
                        String owner,
                        String fieldName,
                        String fieldDescriptor
                ) {
                    int instructionIndex =
                            claimInstructionIndex();

                    EvidenceFact fieldEvidence =
                            buildInstructionEvidence(
                                    symbol,
                                    currentSourceLine,
                                    instructionIndex,
                                    opcode,
                                    "field_access",
                                    owner,
                                    fieldName,
                                    fieldDescriptor,
                                    null
                            );
                    sink.addEvidence(fieldEvidence);

                    sink.addRelation(RelationFact.builder()
                            .kind(RelationKind.ACCESSES_FIELD)
                            .srcSymbol(symbol)
                            .dstSymbol(SymbolIdFactory.field(
                                    internalNameToQualified(owner),
                                    fieldName
                            ))
                            .evidenceIds(
                                    List.of(fieldEvidence.id())
                            )
                            .resolution(
                                    RelationResolutionFactory.resolved()
                            )
                            .origin(FactOriginKind.BYTECODE)
                            .callSiteLine(currentSourceLine)
                            .confidenceHint(
                                    ConfidenceHints.relation(
                                            ResolutionStatus.RESOLVED,
                                            FactOriginKind.BYTECODE
                                    )
                            )
                            .build());

                    super.visitFieldInsn(
                            opcode,
                            owner,
                            fieldName,
                            fieldDescriptor
                    );
                }

                @Override
                public void visitInsn(int opcode) {
                    claimInstructionIndex();
                    super.visitInsn(opcode);
                }

                @Override
                public void visitIntInsn(
                        int opcode,
                        int operand
                ) {
                    claimInstructionIndex();
                    super.visitIntInsn(opcode, operand);
                }

                @Override
                public void visitVarInsn(
                        int opcode,
                        int variableIndex
                ) {
                    claimInstructionIndex();
                    super.visitVarInsn(
                            opcode,
                            variableIndex
                    );
                }

                @Override
                public void visitTypeInsn(
                        int opcode,
                        String type
                ) {
                    claimInstructionIndex();
                    super.visitTypeInsn(opcode, type);
                }

                @Override
                public void visitInvokeDynamicInsn(
                        String invokedName,
                        String invokedDescriptor,
                        Handle bootstrapMethodHandle,
                        Object... bootstrapMethodArguments
                ) {
                    claimInstructionIndex();
                    super.visitInvokeDynamicInsn(
                            invokedName,
                            invokedDescriptor,
                            bootstrapMethodHandle,
                            bootstrapMethodArguments
                    );
                }

                @Override
                public void visitJumpInsn(
                        int opcode,
                        Label label
                ) {
                    claimInstructionIndex();
                    super.visitJumpInsn(opcode, label);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    claimInstructionIndex();
                    super.visitLdcInsn(value);
                }

                @Override
                public void visitIincInsn(
                        int variableIndex,
                        int increment
                ) {
                    claimInstructionIndex();
                    super.visitIincInsn(
                            variableIndex,
                            increment
                    );
                }

                @Override
                public void visitTableSwitchInsn(
                        int minimum,
                        int maximum,
                        Label defaultLabel,
                        Label... labels
                ) {
                    claimInstructionIndex();
                    super.visitTableSwitchInsn(
                            minimum,
                            maximum,
                            defaultLabel,
                            labels
                    );
                }

                @Override
                public void visitLookupSwitchInsn(
                        Label defaultLabel,
                        int[] keys,
                        Label[] labels
                ) {
                    claimInstructionIndex();
                    super.visitLookupSwitchInsn(
                            defaultLabel,
                            keys,
                            labels
                    );
                }

                @Override
                public void visitMultiANewArrayInsn(
                        String arrayDescriptor,
                        int dimensions
                ) {
                    claimInstructionIndex();
                    super.visitMultiANewArrayInsn(
                            arrayDescriptor,
                            dimensions
                    );
                }

                @Override
                public void visitEnd() {
                    if (context.includeObservations()) {
                        addMethodObservationsFromAnnotations(
                                name,
                                symbol,
                                methodSignature,
                                methodAnnotations,
                                typeAnnotations,
                                typeSymbol,
                                sink
                        );
                    }
                    super.visitEnd();
                }

                private int claimInstructionIndex() {
                    return nextInstructionIndex++;
                }
            };
        }

        @Override
        public void visitEnd() {
            if (context.includeObservations()) {
                addTypeObservationsFromAnnotations(
                        typeQualifiedName,
                        typeSymbol,
                        typeAnnotations,
                        sink
                );
            }
            super.visitEnd();
        }

        private void registerAnnotationRelationEvidence(
                AnnotationData annotation,
                ExtractionSink sink
        ) {
            if (annotation == null) {
                return;
            }

            EvidenceFact relationEvidence =
                    buildAnnotationEvidence(
                            annotation,
                            "annotation"
                    );

            annotation.relationEvidenceId =
                    relationEvidence.id();

            sink.addEvidence(relationEvidence);

            addAnnotationRelation(
                    annotation.ownerSymbol,
                    annotation.descriptor,
                    annotation.runtimeVisible,
                    relationEvidence.id(),
                    sink
            );
        }

        private EvidenceFact registerSemanticAnnotationEvidence(
                AnnotationData annotation,
                String role,
                ExtractionSink sink
        ) {
            EvidenceFact evidence =
                    buildAnnotationEvidence(
                            annotation,
                            role
                    );

            sink.addEvidence(evidence);
            return evidence;
        }

        private EvidenceFact buildAnnotationEvidence(
                AnnotationData annotation,
                String role
        ) {
            return BytecodeAnnotationEvidenceFactory.create(
                    annotation.relativePath,
                    annotation.module,
                    annotation.ownerSymbol,
                    annotation.descriptor,
                    annotation.qualifiedName,
                    annotation.runtimeVisible,
                    annotation.annotationIndex,
                    role,
                    annotation.values
            );
        }

        private EvidenceFact buildInstructionEvidence(
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
            return BytecodeInstructionEvidenceFactory.create(
                    relativePath,
                    context.module(),
                    ownerSymbol,
                    sourceLine,
                    instructionIndex,
                    opcode,
                    role,
                    targetOwner,
                    memberName,
                    descriptor,
                    interfaceCall
            );
        }

        private EvidenceFact buildBytecodeEvidence(String symbol) {
            return EvidenceFact.builder()
                    .id(EvidenceIdGenerator.generate(
                            EvidenceType.BYTECODE,
                            relativePath,
                            null,
                            null,
                            null,
                            null,
                            symbol
                    ))
                    .type(EvidenceType.BYTECODE)
                    .path(relativePath)
                    .symbol(symbol)
                    .hash(Integer.toHexString(relativePath.hashCode()))
                    .attrs(Map.of(
                            "class_file",
                            true,
                            "module",
                            context.module()
                    ))
                    .build();
        }
    }

    private AnnotationVisitor annotationVisitor(
            Map<String, Object> destination,
            Runnable onComplete
    ) {
        AnnotationVisitor delegate =
                annotationVisitor(destination);

        return new AnnotationVisitor(
                ASM_API,
                delegate
        ) {
            @Override
            public void visitEnd() {
                super.visitEnd();

                if (onComplete != null) {
                    onComplete.run();
                }
            }
        };
    }

    private AnnotationVisitor annotationVisitor(Map<String, Object> destination) {
        return new AnnotationVisitor(ASM_API) {
            @Override
            public void visit(String name, Object value) {
                if (name != null) {
                    destination.put(name, normalizeAnnotationValue(value));
                }
            }

            @Override
            public void visitEnum(
                    String name,
                    String descriptor,
                    String value
            ) {
                if (name != null) {
                    destination.put(name, value);
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(
                    String name,
                    String descriptor
            ) {
                Map<String, Object> nested = new LinkedHashMap<>();
                if (name != null) {
                    destination.put(name, nested);
                }
                return annotationVisitor(nested);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Object> values = new ArrayList<>();
                if (name != null) {
                    destination.put(name, values);
                }
                return annotationArrayVisitor(values);
            }
        };
    }

    private AnnotationVisitor annotationArrayVisitor(List<Object> destination) {
        return new AnnotationVisitor(ASM_API) {
            @Override
            public void visit(String name, Object value) {
                destination.add(normalizeAnnotationValue(value));
            }

            @Override
            public void visitEnum(
                    String name,
                    String descriptor,
                    String value
            ) {
                destination.add(value);
            }

            @Override
            public AnnotationVisitor visitAnnotation(
                    String name,
                    String descriptor
            ) {
                Map<String, Object> nested = new LinkedHashMap<>();
                destination.add(nested);
                return annotationVisitor(nested);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Object> nested = new ArrayList<>();
                destination.add(nested);
                return annotationArrayVisitor(nested);
            }
        };
    }

    private Object normalizeAnnotationValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Type type) {
            return type.getClassName();
        }

        Class<?> valueType = value.getClass();
        if (!valueType.isArray()) {
            return value;
        }

        List<Object> values = new ArrayList<>();
        int length = Array.getLength(value);
        for (int index = 0; index < length; index++) {
            values.add(normalizeAnnotationValue(Array.get(value, index)));
        }
        return values;
    }

    private void addTypeObservationsFromAnnotations(
            String typeQualifiedName,
            String typeSymbol,
            List<AnnotationData> annotations,
            ExtractionSink sink
    ) {
        List<AnnotationData> providerAnnotations = matchingAnnotations(
                annotations,
                this::isProviderTypeAnnotationName
        );

        if (!providerAnnotations.isEmpty()) {
            AnnotationData providerAnnotation = providerAnnotations.get(0);
            List<String> beanNames = extractTypeProviderBeanNames(
                    typeQualifiedName,
                    providerAnnotations
            );
            List<String> qualifiers = extractQualifierValues(
                    annotations,
                    defaultBeanName(simpleName(typeQualifiedName))
            );
            boolean primary = hasAnnotationSuffix(annotations, "Primary");

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("provider_kind", providerKind(providerAnnotation.qualifiedName));
            attrs.put("bean_names", beanNames);
            attrs.put("provided_type", typeQualifiedName);
            attrs.put("primary", primary);
            attrs.put("qualifiers", qualifiers);
            attrs.put("annotations", annotationNames(annotations));
            attrs.put("provider_annotation", providerAnnotation.qualifiedName);

            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.DI_PROVIDER)
                    .siteSymbol(typeSymbol)
                    .targetTypeRef(TypeRefFactory.simple(typeQualifiedName))
                    .evidenceIds(
                            registerSemanticAnnotationEvidenceIds(
                                    annotations,
                                    "bean_provider",
                                    annotationName ->
                                            isProviderTypeAnnotationName(
                                                    annotationName
                                            )
                                                    || annotationName.endsWith(
                                                    "Primary"
                                            )
                                                    || annotationName.endsWith(
                                                    "Qualifier"
                                            )
                                                    || annotationName.endsWith(
                                                    "Named"
                                            ),
                                    sink
                            )
                    )
                    .origin(FactOriginKind.BYTECODE)
                    .confidenceHint(0.9)
                    .note("dependency injection provider")
                    .attrs(attrs)
                    .build());
        }

        List<AnnotationData> configurationAnnotations = matchingAnnotations(
                annotations,
                this::isConfigurationAnnotationName
        );

        if (configurationAnnotations.isEmpty()) {
            return;
        }

        List<String> basePackageClasses = extractClassNames(
                annotations,
                "ComponentScan",
                Set.of("basePackageClasses")
        );

        LinkedHashSet<String> componentScanPackages = new LinkedHashSet<>(
                extractStringValues(
                        annotations,
                        "ComponentScan",
                        Set.of("value", "basePackages")
                )
        );
        for (String basePackageClass : basePackageClasses) {
            String packageName = packageName(basePackageClass);
            if (!packageName.isBlank() && !"(default)".equals(packageName)) {
                componentScanPackages.add(packageName);
            }
        }

        List<String> configurationKinds = configurationKinds(configurationAnnotations);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("configuration_kind", configurationKinds.get(0));
        attrs.put("configuration_kinds", configurationKinds);
        attrs.put(
                "imported_types",
                extractClassNames(
                        annotations,
                        "Import",
                        Set.of("value")
                )
        );
        attrs.put(
                "component_scan_packages",
                List.copyOf(componentScanPackages)
        );
        attrs.put(
                "component_scan_base_package_classes",
                basePackageClasses
        );
        attrs.put("annotations", annotationNames(annotations));

        sink.addObservation(ObservationFact.builder()
                .kind(ObservationKind.CONFIG_WIRING)
                .siteSymbol(typeSymbol)
                .evidenceIds(
                        registerSemanticAnnotationEvidenceIds(
                                annotations,
                                "configuration_wiring",
                                this::isConfigurationAnnotationName,
                                sink
                        )
                )
                .origin(FactOriginKind.BYTECODE)
                .confidenceHint(0.9)
                .note("configuration wiring")
                .attrs(attrs)
                .build());
    }

    private void addMethodObservationsFromAnnotations(
            String methodName,
            String methodSymbol,
            SignatureFact methodSignature,
            List<AnnotationData> methodAnnotations,
            List<AnnotationData> ownerTypeAnnotations,
            String ownerTypeSymbol,
            ExtractionSink sink
    ) {
        List<AnnotationData> providerAnnotations = matchingAnnotations(
                methodAnnotations,
                this::isBeanAnnotationName
        );

        if (!providerAnnotations.isEmpty()) {
            AnnotationData providerAnnotation = providerAnnotations.get(0);
            TypeRef providedType = methodSignature.returns();

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put(
                    "provider_kind",
                    methodProviderKind(providerAnnotation.qualifiedName)
            );
            attrs.put(
                    "bean_names",
                    extractMethodProviderBeanNames(
                            methodName,
                            providerAnnotation,
                            methodAnnotations
                    )
            );
            attrs.put(
                    "provided_type",
                    providedType == null ? null : providedType.raw()
            );
            attrs.put(
                    "primary",
                    hasAnnotationSuffix(methodAnnotations, "Primary")
            );
            attrs.put(
                    "qualifiers",
                    extractQualifierValues(methodAnnotations, methodName)
            );
            attrs.put("owner_config_symbol", ownerTypeSymbol);
            attrs.put(
                    "owner_is_configuration",
                    hasAnnotationSuffix(ownerTypeAnnotations, "Configuration")
            );
            attrs.put("annotations", annotationNames(methodAnnotations));
            attrs.put("provider_annotation", providerAnnotation.qualifiedName);

            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.DI_PROVIDER)
                    .siteSymbol(methodSymbol)
                    .targetTypeRef(providedType)
                    .evidenceIds(
                            registerSemanticAnnotationEvidenceIds(
                                    methodAnnotations,
                                    "bean_provider",
                                    annotationName ->
                                            isBeanAnnotationName(
                                                    annotationName
                                            )
                                                    || annotationName.endsWith(
                                                    "Primary"
                                            )
                                                    || annotationName.endsWith(
                                                    "Qualifier"
                                            )
                                                    || annotationName.endsWith(
                                                    "Named"
                                            ),
                                    sink
                            )
                    )
                    .origin(FactOriginKind.BYTECODE)
                    .confidenceHint(0.9)
                    .note("dependency injection provider")
                    .attrs(attrs)
                    .build());
        }

        if (methodAnnotations.stream()
                .map(annotation -> annotation.qualifiedName)
                .anyMatch(this::isEventListenerAnnotationName)) {
            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.EVENT_SUBSCRIPTION)
                    .siteSymbol(methodSymbol)
                    .targetTypeRef(
                            methodSignature.params().isEmpty()
                                    ? null
                                    : methodSignature.params().get(0).typeRef()
                    )
                    .evidenceIds(
                            registerSemanticAnnotationEvidenceIds(
                                    methodAnnotations,
                                    "event_subscription",
                                    this::isEventListenerAnnotationName,
                                    sink
                            )
                    )
                    .origin(FactOriginKind.OBSERVED)
                    .confidenceHint(ConfidenceHints.observation(
                            List.of(EvidenceType.BYTECODE)
                    ))
                    .note("event subscriber from bytecode annotation")
                    .attrs(Map.of(
                            "annotation",
                            methodAnnotations.stream()
                                    .map(annotation -> annotation.qualifiedName)
                                    .filter(this::isEventListenerAnnotationName)
                                    .findFirst()
                                    .orElse("")
                    ))
                    .build());
        }
    }

    private List<String> registerSemanticAnnotationEvidenceIds(
            List<AnnotationData> annotations,
            String role,
            Predicate<String> matcher,
            ExtractionSink sink
    ) {
        if (annotations == null
                || annotations.isEmpty()
                || matcher == null) {
            return List.of();
        }

        LinkedHashSet<String> evidenceIds =
                new LinkedHashSet<>();

        for (AnnotationData annotation : annotations) {
            if (annotation == null
                    || annotation.qualifiedName == null
                    || !matcher.test(
                    annotation.qualifiedName
            )) {
                continue;
            }

            EvidenceFact evidence =
                    BytecodeAnnotationEvidenceFactory.create(
                            annotation.relativePath,
                            annotation.module,
                            annotation.ownerSymbol,
                            annotation.descriptor,
                            annotation.qualifiedName,
                            annotation.runtimeVisible,
                            annotation.annotationIndex,
                            role,
                            annotation.values
                    );

            sink.addEvidence(evidence);
            evidenceIds.add(evidence.id());
        }

        return List.copyOf(evidenceIds);
    }

    private List<AnnotationData> matchingAnnotations(
            List<AnnotationData> annotations,
            Predicate<String> matcher
    ) {
        if (annotations == null || annotations.isEmpty()) {
            return List.of();
        }

        return annotations.stream()
                .filter(annotation -> annotation != null)
                .filter(annotation -> matcher.test(annotation.qualifiedName))
                .toList();
    }

    private Set<String> annotationNames(List<AnnotationData> annotations) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (annotations != null) {
            for (AnnotationData annotation : annotations) {
                if (annotation != null
                        && annotation.qualifiedName != null
                        && !annotation.qualifiedName.isBlank()) {
                    names.add(annotation.qualifiedName);
                }
            }
        }
        return Set.copyOf(names);
    }

    private boolean hasAnnotationSuffix(
            List<AnnotationData> annotations,
            String suffix
    ) {
        if (annotations == null || suffix == null) {
            return false;
        }

        return annotations.stream()
                .filter(annotation -> annotation != null)
                .map(annotation -> annotation.qualifiedName)
                .anyMatch(name -> name != null && name.endsWith(suffix));
    }

    private String providerKind(String annotationName) {
        if (annotationName.endsWith("RestController")) {
            return "rest_controller_type";
        }
        if (annotationName.endsWith("Controller")) {
            return "controller_type";
        }
        if (annotationName.endsWith("Service")) {
            return "service_type";
        }
        if (annotationName.endsWith("Repository")) {
            return "repository_type";
        }
        if (annotationName.endsWith("Named")) {
            return "named_type";
        }
        return "component_type";
    }

    private String methodProviderKind(String annotationName) {
        return annotationName.endsWith("Produces")
                ? "producer_method"
                : "bean_method";
    }

    private List<String> extractTypeProviderBeanNames(
            String typeQualifiedName,
            List<AnnotationData> providerAnnotations
    ) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (AnnotationData annotation : providerAnnotations) {
            collectStringValues(annotation.values.get("value"), names);
            collectStringValues(annotation.values.get("name"), names);
        }

        if (names.isEmpty()) {
            names.add(defaultBeanName(simpleName(typeQualifiedName)));
        }

        return List.copyOf(names);
    }

    private List<String> extractMethodProviderBeanNames(
            String methodName,
            AnnotationData providerAnnotation,
            List<AnnotationData> methodAnnotations
    ) {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        if (providerAnnotation.qualifiedName.endsWith("Bean")) {
            collectStringValues(providerAnnotation.values.get("value"), names);
            collectStringValues(providerAnnotation.values.get("name"), names);
        }

        for (AnnotationData annotation : methodAnnotations) {
            if (annotation.qualifiedName.endsWith("Named")) {
                collectStringValues(annotation.values.get("value"), names);
            }
        }

        if (names.isEmpty()) {
            names.add(methodName);
        }

        return List.copyOf(names);
    }

    private List<String> extractQualifierValues(
            List<AnnotationData> annotations,
            String defaultValue
    ) {
        LinkedHashSet<String> qualifiers = new LinkedHashSet<>();

        for (AnnotationData annotation : annotations) {
            if (!annotation.qualifiedName.endsWith("Qualifier")
                    && !annotation.qualifiedName.endsWith("Named")) {
                continue;
            }

            LinkedHashSet<String> values = new LinkedHashSet<>();
            collectStringValues(annotation.values.get("value"), values);
            collectStringValues(annotation.values.get("name"), values);

            if (values.isEmpty()
                    && annotation.qualifiedName.endsWith("Named")
                    && defaultValue != null
                    && !defaultValue.isBlank()) {
                qualifiers.add(defaultValue);
            } else {
                qualifiers.addAll(values);
            }
        }

        return List.copyOf(qualifiers);
    }

    private List<String> configurationKinds(
            List<AnnotationData> annotations
    ) {
        LinkedHashSet<String> kinds = new LinkedHashSet<>();

        for (AnnotationData annotation : annotations) {
            String annotationName = annotation.qualifiedName;
            if (annotationName.endsWith("Configuration")) {
                kinds.add("spring_configuration");
            } else if (annotationName.endsWith("Import")) {
                kinds.add("spring_import");
            } else if (annotationName.endsWith("ComponentScan")) {
                kinds.add("spring_component_scan");
            }
        }

        if (kinds.isEmpty()) {
            kinds.add("configuration");
        }

        return List.copyOf(kinds);
    }

    private List<String> extractStringValues(
            List<AnnotationData> annotations,
            String annotationSuffix,
            Set<String> attributeNames
    ) {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (AnnotationData annotation : annotations) {
            if (!annotation.qualifiedName.endsWith(annotationSuffix)) {
                continue;
            }
            for (String attributeName : attributeNames) {
                collectStringValues(annotation.values.get(attributeName), result);
            }
        }

        return List.copyOf(result);
    }

    private List<String> extractClassNames(
            List<AnnotationData> annotations,
            String annotationSuffix,
            Set<String> attributeNames
    ) {
        return extractStringValues(
                annotations,
                annotationSuffix,
                attributeNames
        );
    }

    private void collectStringValues(
            Object value,
            Set<String> destination
    ) {
        if (value == null || destination == null) {
            return;
        }

        if (value instanceof String text) {
            if (!text.isBlank()) {
                destination.add(text);
            }
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectStringValues(item, destination);
            }
            return;
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                collectStringValues(Array.get(value, index), destination);
            }
        }
    }

    private String defaultBeanName(String simpleTypeName) {
        if (simpleTypeName == null || simpleTypeName.isBlank()) {
            return "";
        }

        if (simpleTypeName.length() > 1
                && Character.isUpperCase(simpleTypeName.charAt(0))
                && Character.isUpperCase(simpleTypeName.charAt(1))) {
            return simpleTypeName;
        }

        return Character.toLowerCase(simpleTypeName.charAt(0))
                + simpleTypeName.substring(1);
    }

    private String ensureModuleSymbol(ExtractionContext context, ExtractionSink sink) {
        return SymbolIdFactory.module(context.module());
    }

    private String ensurePackageSymbol(
            ExtractionContext context,
            String moduleSymbol,
            String packageName,
            String relativePath,
            ExtractionSink sink
    ) {
        return SymbolIdFactory.packageSymbol(packageName);
    }

    private SignatureFact methodSignature(String descriptor, String[] exceptions) {
        Type methodType = Type.getMethodType(descriptor);
        List<ParamFact> params = new ArrayList<>();
        for (Type argumentType : methodType.getArgumentTypes()) {
            params.add(ParamFact.builder()
                    .name(null)
                    .typeRef(typeRefFromAsmType(argumentType))
                    .build());
        }

        List<TypeRef> throwsTypes = new ArrayList<>();
        if (exceptions != null) {
            for (String exception : exceptions) {
                throwsTypes.add(objectTypeRef(exception));
            }
        }

        return SignatureFact.builder()
                .params(params)
                .returns(typeRefFromAsmType(methodType.getReturnType()))
                .throwsTypes(throwsTypes)
                .build();
    }

    private void addAnnotationRelation(
            String sourceSymbol,
            String annotationDescriptor,
            boolean runtimeVisible,
            String evidenceId,
            ExtractionSink sink
    ) {
        if (sourceSymbol == null
                || sourceSymbol.isBlank()
                || annotationDescriptor == null
                || annotationDescriptor.isBlank()
                || evidenceId == null
                || evidenceId.isBlank()) {
            return;
        }

        final String annotationQualifiedName;

        try {
            annotationQualifiedName = annotationQualifiedName(annotationDescriptor);
        } catch (Exception e) {
            sink.addWarning(
                    "failed to resolve bytecode annotation descriptor: "
                            + annotationDescriptor
                            + " ("
                            + e.getClass().getSimpleName()
                            + ")"
            );
            return;
        }

        if (annotationQualifiedName == null || annotationQualifiedName.isBlank()) {
            return;
        }

        sink.addRelation(RelationFact.builder()
                .kind(RelationKind.ANNOTATED_WITH)
                .srcSymbol(sourceSymbol)
                .dstSymbol(SymbolIdFactory.type(annotationQualifiedName))
                .evidenceIds(List.of(evidenceId))
                .resolution(RelationResolutionFactory.resolved())
                .origin(FactOriginKind.BYTECODE)
                .confidenceHint(ConfidenceHints.relation(
                        ResolutionStatus.RESOLVED,
                        FactOriginKind.BYTECODE
                ))
                .attrs(Map.of(
                        "descriptor",
                        annotationDescriptor,
                        "runtime_visible",
                        runtimeVisible
                ))
                .build());
    }

    private String annotationQualifiedName(String descriptor) {
        TypeRef typeRef = descriptorToTypeRef(descriptor);
        return typeRef == null ? "" : typeRef.raw();
    }

    /**
     * 수집 대상에서 제외할 컴파일러 생성 어노테이션인지 판단한다.
     * 판정 후 visitAnnotation이 null을 반환하면 ASM은 해당 어노테이션의
     * 값 방문 자체를 건너뛰므로, 값 파싱 비용도 함께 사라진다.
     */
    private boolean isCompilerGeneratedAnnotation(String qualifiedName) {
        return qualifiedName != null
                && COMPILER_GENERATED_ANNOTATIONS.contains(qualifiedName);
    }

    private TypeRef descriptorToTypeRef(String descriptor) {
        return typeRefFromAsmType(Type.getType(descriptor));
    }

    private TypeRef objectTypeRef(String internalName) {
        return TypeRefFactory.simple(internalNameToQualified(internalName));
    }

    private TypeRef typeRefFromAsmType(Type type) {
        if (type.getSort() == Type.VOID) {
            return TypeRef.builder()
                    .raw("void")
                    .primitive(true)
                    .arrayDim(0)
                    .unresolved(false)
                    .build();
        }

        if (type.getSort() == Type.BOOLEAN
                || type.getSort() == Type.CHAR
                || type.getSort() == Type.BYTE
                || type.getSort() == Type.SHORT
                || type.getSort() == Type.INT
                || type.getSort() == Type.FLOAT
                || type.getSort() == Type.LONG
                || type.getSort() == Type.DOUBLE) {
            return TypeRef.builder()
                    .raw(type.getClassName())
                    .primitive(true)
                    .arrayDim(0)
                    .unresolved(false)
                    .build();
        }

        if (type.getSort() == Type.ARRAY) {
            Type elementType = type.getElementType();
            TypeRef element = typeRefFromAsmType(elementType);
            return TypeRef.builder()
                    .raw(element.raw())
                    .args(element.args())
                    .arrayDim(type.getDimensions())
                    .primitive(element.primitive())
                    .unresolved(false)
                    .build();
        }

        return TypeRef.builder()
                .raw(type.getClassName())
                .primitive(false)
                .arrayDim(0)
                .unresolved(false)
                .build();
    }

    private AccessLevel accessLevel(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return AccessLevel.PUBLIC;
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return AccessLevel.PROTECTED;
        }
        if ((access & Opcodes.ACC_PRIVATE) != 0) {
            return AccessLevel.PRIVATE;
        }
        return AccessLevel.PACKAGE_PRIVATE;
    }

    private Set<Modifier> modifierKinds(int access) {
        EnumSet<Modifier> set = EnumSet.noneOf(Modifier.class);
        if ((access & Opcodes.ACC_STATIC) != 0) {
            set.add(Modifier.STATIC);
        }
        if ((access & Opcodes.ACC_FINAL) != 0) {
            set.add(Modifier.FINAL);
        }
        if ((access & Opcodes.ACC_ABSTRACT) != 0) {
            set.add(Modifier.ABSTRACT);
        }
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            set.add(Modifier.SYNCHRONIZED);
        }
        if ((access & Opcodes.ACC_NATIVE) != 0) {
            set.add(Modifier.NATIVE);
        }
        if ((access & Opcodes.ACC_STRICT) != 0) {
            set.add(Modifier.STRICTFP);
        }
        if ((access & Opcodes.ACC_TRANSIENT) != 0) {
            set.add(Modifier.TRANSIENT);
        }
        if ((access & Opcodes.ACC_VOLATILE) != 0) {
            set.add(Modifier.VOLATILE);
        }
        return set;
    }

    private TypeKind typeKind(int access) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) {
            return TypeKind.ANNOTATION;
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return TypeKind.ENUM;
        }
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return TypeKind.INTERFACE;
        }
        if ((access & Opcodes.ACC_RECORD) != 0) {
            return TypeKind.RECORD;
        }
        return TypeKind.CLASS;
    }

    private String internalNameToQualified(String internalName) {
        return internalName.replace('/', '.');
    }

    private String packageName(String qualifiedName) {
        int index = qualifiedName.lastIndexOf('.');
        return index < 0 ? "(default)" : qualifiedName.substring(0, index);
    }

    private String simpleName(String qualifiedName) {
        int index = qualifiedName.lastIndexOf('.');
        return index < 0 ? qualifiedName : qualifiedName.substring(index + 1);
    }

    private boolean isInjectionAnnotation(String descriptor) {
        return isInjectionAnnotationName(annotationQualifiedName(descriptor));
    }

    private boolean isInjectionAnnotationName(String qualified) {
        return qualified.endsWith("Inject")
                || qualified.endsWith("Autowired")
                || qualified.endsWith("Resource")
                || qualified.endsWith("Qualifier");
    }

    private boolean isBeanAnnotationName(String qualified) {
        return qualified.endsWith("Bean") || qualified.endsWith("Produces");
    }

    private boolean isProviderTypeAnnotationName(String qualified) {
        return qualified.endsWith("Component")
                || qualified.endsWith("Service")
                || qualified.endsWith("Repository")
                || qualified.endsWith("Controller")
                || qualified.endsWith("RestController")
                || qualified.endsWith("Named");
    }

    private boolean isConfigurationAnnotationName(String qualified) {
        return qualified.endsWith("Configuration")
                || qualified.endsWith("Import")
                || qualified.endsWith("ComponentScan");
    }

    private boolean isEventListenerAnnotationName(String qualified) {
        return qualified.endsWith("EventListener")
                || qualified.endsWith("Subscribe");
    }

    private boolean isReflectionOwner(String owner, String methodName) {
        String qualified = internalNameToQualified(owner);
        return ("java.lang.Class".equals(qualified)
                && ("forName".equals(methodName)
                || "getMethod".equals(methodName)
                || "getDeclaredMethod".equals(methodName)))
                || ("java.lang.reflect.Method".equals(qualified)
                && "invoke".equals(methodName))
                || ("java.lang.reflect.Constructor".equals(qualified)
                && "newInstance".equals(methodName));
    }
}

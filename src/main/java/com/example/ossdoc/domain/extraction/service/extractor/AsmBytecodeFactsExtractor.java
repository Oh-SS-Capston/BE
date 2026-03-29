package com.example.ossdoc.domain.extraction.service.extractor;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;

import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.SignatureFact;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.AccessLevel;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.EvidenceKind;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.ModifierKind;
import com.example.ossdoc.domain.extraction.enums.ObservationKind;
import com.example.ossdoc.domain.extraction.enums.RelationKind;
import com.example.ossdoc.domain.extraction.enums.SymbolFactKind;
import com.example.ossdoc.domain.extraction.enums.SymbolOriginKind;
import com.example.ossdoc.domain.extraction.enums.TypeKind;
import com.example.ossdoc.domain.extraction.service.support.util.EvidenceIdGenerator;
import com.example.ossdoc.domain.extraction.service.support.util.RelationResolutionFactory;
import com.example.ossdoc.domain.extraction.service.support.util.RepoPathUtils;
import com.example.ossdoc.domain.extraction.service.support.util.SymbolIdFactory;
import com.example.ossdoc.domain.extraction.service.support.util.TypeRefFactory;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ASM 기반 bytecode extractor.
 *
 * 새 구조에서는 planner가 확정한 class 파일 목록만 처리한다.
 */
@Component
public class AsmBytecodeFactsExtractor implements FactsExtractor {

    private static final int ASM_API = Opcodes.ASM9;

    @Override
    public ChunkKind supports() {
        return ChunkKind.ASM;
    }

    @Override
    public ChunkResult extract(ExtractionContext context) {
        ExtractionSink sink = new ExtractionSink(context.chunkKind());

        if (!context.isAsmChunk()) {
            sink.addError("ASM extractor received non-ASM chunk: " + context.chunkKind());
            return sink.toChunkResult(context.chunk());
        }

        if (!context.hasFiles()) {
            sink.addWarning("ASM chunk contains no files: " + context.chunkId());
            return sink.toChunkResult(context.chunk());
        }

        for (Path classFile : context.files()) {
            if (classFile == null || !Files.exists(classFile) || !Files.isRegularFile(classFile)) {
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

    private void visitClassFile(ExtractionContext context, Path classFile, ExtractionSink sink) {
        String relativePath = RepoPathUtils.toRepoRelative(context.repoRoot(), classFile);
        sink.recordFileScanned();

        try {
            ClassReader classReader = new ClassReader(Files.readAllBytes(classFile));
            classReader.accept(new AsmFactsVisitor(context, classFile, relativePath, sink), ClassReader.SKIP_FRAMES);
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

        private String packageName;
        private String packageSymbol;
        private String typeQualifiedName;
        private String typeSymbol;
        private String typeEvidenceId;

        private AsmFactsVisitor(ExtractionContext context, Path classFile, String relativePath, ExtractionSink sink) {
            super(ASM_API);
            this.context = context;
            this.classFile = classFile;
            this.relativePath = relativePath;
            this.sink = sink;
            this.moduleSymbol = ensureModuleSymbol(context, sink);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.typeQualifiedName = internalNameToQualified(name);
            this.typeSymbol = SymbolIdFactory.type(typeQualifiedName);
            this.packageName = packageName(typeQualifiedName);
            this.packageSymbol = ensurePackageSymbol(context, moduleSymbol, packageName, relativePath, sink);

            EvidenceFact typeEvidence = buildBytecodeEvidence(typeSymbol);
            this.typeEvidenceId = typeEvidence.id();
            sink.addEvidence(typeEvidence);
            sink.addSymbol(SymbolFact.builder()
                    .symbol(typeSymbol)
                    .kind(SymbolFactKind.TYPE)
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
                    .isSynthetic((access & Opcodes.ACC_SYNTHETIC) != 0)
                    .bytecodeDescriptor(name)
                    .build());

            sink.addRelation(RelationFact.builder()
                    .kind(RelationKind.CONTAINS)
                    .srcSymbol(packageSymbol)
                    .dstSymbol(typeSymbol)
                    .evidenceIds(List.of(typeEvidence.id()))
                    .resolution(RelationResolutionFactory.resolved())
                    .origin(FactOriginKind.BYTECODE)
                    .build());

            if (superName != null && !"java/lang/Object".equals(superName)) {
                addTypeRelation(RelationKind.EXTENDS, typeSymbol, objectTypeRef(superName), typeEvidence.id());
            }
            if (interfaces != null) {
                for (String iface : interfaces) {
                    addTypeRelation(RelationKind.IMPLEMENTS, typeSymbol, objectTypeRef(iface), typeEvidence.id());
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addAnnotatedBy(typeSymbol, descriptorToTypeRef(descriptor), typeEvidenceId);
            if (context.includeObservations() && isProviderTypeAnnotation(descriptor)) {
                sink.addObservation(ObservationFact.builder()
                        .kind(ObservationKind.DI_PROVIDER)
                        .siteSymbol(typeSymbol)
                        .evidenceIds(List.of(typeEvidenceId))
                        .origin(FactOriginKind.OBSERVED)
                        .note("type-level provider annotation from bytecode")
                        .attrs(Map.of("annotation", descriptorToTypeRef(descriptor).raw()))
                        .build());
            }
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            String fieldSymbol = SymbolIdFactory.field(typeQualifiedName, name);
            EvidenceFact evidence = buildBytecodeEvidence(fieldSymbol);
            sink.addEvidence(evidence);

            TypeRef fieldTypeRef = typeRefFromAsmType(Type.getType(descriptor));
            sink.addSymbol(SymbolFact.builder()
                    .symbol(fieldSymbol)
                    .kind(SymbolFactKind.FIELD)
                    .name(name)
                    .ownerTypeSymbol(typeSymbol)
                    .module(context.module())
                    .bytecodeRoot(context.bytecodeRootString())
                    .access(accessLevel(access))
                    .modifiers(modifierKinds(access))
                    .origin(SymbolOriginKind.BYTECODE)
                    .evidenceIds(List.of(evidence.id()))
                    .signature(SignatureFact.builder().fieldType(fieldTypeRef).build())
                    .sourceFile(relativePath)
                    .isSynthetic((access & Opcodes.ACC_SYNTHETIC) != 0)
                    .build());

            sink.addRelation(RelationFact.builder()
                    .kind(RelationKind.CONTAINS)
                    .srcSymbol(typeSymbol)
                    .dstSymbol(fieldSymbol)
                    .evidenceIds(List.of(evidence.id()))
                    .resolution(RelationResolutionFactory.resolved())
                    .origin(FactOriginKind.BYTECODE)
                    .build());
            addTypeRelation(RelationKind.FIELD_TYPE, fieldSymbol, fieldTypeRef, evidence.id());

            return new FieldVisitor(ASM_API) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    addAnnotatedBy(fieldSymbol, descriptorToTypeRef(annotationDescriptor), evidence.id());

                    if (context.includeObservations() && isInjectionAnnotation(annotationDescriptor)) {
                        sink.addObservation(ObservationFact.builder()
                                .kind(ObservationKind.DI_INJECTION_SITE)
                                .siteSymbol(fieldSymbol)
                                .targetTypeRef(fieldTypeRef)
                                .evidenceIds(List.of(evidence.id()))
                                .origin(FactOriginKind.OBSERVED)
                                .note("field injection from bytecode annotation")
                                .attrs(Map.of("annotation", descriptorToTypeRef(annotationDescriptor).raw()))
                                .build());
                    }
                    return super.visitAnnotation(annotationDescriptor, visible);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            boolean constructor = "<init>".equals(name);
            SignatureFact methodSignature = methodSignature(descriptor, exceptions);
            String symbol = constructor
                    ? SymbolIdFactory.constructor(typeQualifiedName, methodSignature)
                    : SymbolIdFactory.method(typeQualifiedName, name, methodSignature);

            EvidenceFact evidence = buildBytecodeEvidence(symbol);
            sink.addEvidence(evidence);
            sink.addSymbol(SymbolFact.builder()
                    .symbol(symbol)
                    .kind(constructor ? SymbolFactKind.CONSTRUCTOR : SymbolFactKind.METHOD)
                    .name(constructor ? simpleName(typeQualifiedName) : name)
                    .ownerTypeSymbol(typeSymbol)
                    .module(context.module())
                    .bytecodeRoot(context.bytecodeRootString())
                    .access(accessLevel(access))
                    .modifiers(modifierKinds(access))
                    .origin(SymbolOriginKind.BYTECODE)
                    .evidenceIds(List.of(evidence.id()))
                    .signature(methodSignature)
                    .sourceFile(relativePath)
                    .isBridge((access & Opcodes.ACC_BRIDGE) != 0)
                    .isSynthetic((access & Opcodes.ACC_SYNTHETIC) != 0)
                    .bytecodeDescriptor(descriptor)
                    .build());

            sink.addRelation(RelationFact.builder()
                    .kind(RelationKind.CONTAINS)
                    .srcSymbol(typeSymbol)
                    .dstSymbol(symbol)
                    .evidenceIds(List.of(evidence.id()))
                    .resolution(RelationResolutionFactory.resolved())
                    .origin(FactOriginKind.BYTECODE)
                    .build());

            for (TypeRef parameterType : methodSignature.params()) {
                addTypeRelation(RelationKind.PARAM_TYPE, symbol, parameterType, evidence.id());
            }
            if (methodSignature.returns() != null) {
                addTypeRelation(RelationKind.RETURN_TYPE, symbol, methodSignature.returns(), evidence.id());
            }
            if (methodSignature.throwsTypes() != null) {
                for (TypeRef thrownType : methodSignature.throwsTypes()) {
                    addTypeRelation(RelationKind.THROWS_TYPE, symbol, thrownType, evidence.id());
                }
            }

            return new MethodVisitor(ASM_API) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    addAnnotatedBy(symbol, descriptorToTypeRef(annotationDescriptor), evidence.id());

                    if (context.includeObservations()) {
                        if (isBeanAnnotation(annotationDescriptor)) {
                            sink.addObservation(ObservationFact.builder()
                                    .kind(ObservationKind.DI_PROVIDER)
                                    .siteSymbol(symbol)
                                    .targetTypeRef(methodSignature.returns())
                                    .evidenceIds(List.of(evidence.id()))
                                    .origin(FactOriginKind.OBSERVED)
                                    .note("provider method from bytecode annotation")
                                    .attrs(Map.of("annotation", descriptorToTypeRef(annotationDescriptor).raw()))
                                    .build());
                        }
                        if (isEventListenerAnnotation(annotationDescriptor)) {
                            sink.addObservation(ObservationFact.builder()
                                    .kind(ObservationKind.EVENT_SUBSCRIBE)
                                    .siteSymbol(symbol)
                                    .targetTypeRef(methodSignature.params().isEmpty() ? null : methodSignature.params().get(0))
                                    .evidenceIds(List.of(evidence.id()))
                                    .origin(FactOriginKind.OBSERVED)
                                    .note("event subscriber from bytecode annotation")
                                    .attrs(Map.of("annotation", descriptorToTypeRef(annotationDescriptor).raw()))
                                    .build());
                        }
                    }
                    return super.visitAnnotation(annotationDescriptor, visible);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                    Type methodType = Type.getMethodType(methodDescriptor);
                    SignatureFact calleeSignature = SignatureFact.builder()
                            .params(Arrays.stream(methodType.getArgumentTypes()).map(AsmBytecodeFactsExtractor.this::typeRefFromAsmType).toList())
                            .build();

                    String dstSymbol = "<init>".equals(methodName)
                            ? SymbolIdFactory.constructor(internalNameToQualified(owner), calleeSignature)
                            : SymbolIdFactory.method(internalNameToQualified(owner), methodName, calleeSignature);

                    sink.addRelation(RelationFact.builder()
                            .kind(RelationKind.CALLS)
                            .srcSymbol(symbol)
                            .dstSymbol(dstSymbol)
                            .evidenceIds(List.of(evidence.id()))
                            .resolution(RelationResolutionFactory.resolved())
                            .origin(FactOriginKind.BYTECODE)
                            .build());

                    if (context.includeObservations() && isReflectionOwner(owner, methodName)) {
                        sink.addObservation(ObservationFact.builder()
                                .kind(ObservationKind.REFLECTION_USE)
                                .siteSymbol(symbol)
                                .evidenceIds(List.of(evidence.id()))
                                .origin(FactOriginKind.OBSERVED)
                                .note("reflection API usage from bytecode")
                                .attrs(Map.of(
                                        "owner", internalNameToQualified(owner),
                                        "method", methodName,
                                        "descriptor", methodDescriptor
                                ))
                                .build());
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                    sink.addRelation(RelationFact.builder()
                            .kind(RelationKind.ACCESSES_FIELD)
                            .srcSymbol(symbol)
                            .dstSymbol(SymbolIdFactory.field(internalNameToQualified(owner), fieldName))
                            .evidenceIds(List.of(evidence.id()))
                            .resolution(RelationResolutionFactory.resolved())
                            .origin(FactOriginKind.BYTECODE)
                            .build());
                }
            };
        }

        private void addAnnotatedBy(String srcSymbol, TypeRef annotationTypeRef, String evidenceId) {
            sink.addRelation(RelationFact.builder()
                    .kind(RelationKind.ANNOTATED_BY)
                    .srcSymbol(srcSymbol)
                    .dstRawRef(annotationTypeRef.raw())
                    .evidenceIds(List.of(evidenceId))
                    .resolution(RelationResolutionFactory.partial("annotation symbol linking deferred"))
                    .origin(FactOriginKind.BYTECODE)
                    .build());
        }

        private void addTypeRelation(RelationKind kind, String srcSymbol, TypeRef dstTypeRef, String evidenceId) {
            sink.addRelation(RelationFact.builder()
                    .kind(kind)
                    .srcSymbol(srcSymbol)
                    .dstRawRef(dstTypeRef.raw())
                    .evidenceIds(List.of(evidenceId))
                    .resolution(RelationResolutionFactory.partial("bytecode type symbol linking deferred"))
                    .origin(FactOriginKind.BYTECODE)
                    .build());
        }

        private EvidenceFact buildBytecodeEvidence(String symbol) {
            return EvidenceFact.builder()
                    .id(EvidenceIdGenerator.generate(EvidenceKind.BYTECODE, relativePath, null, symbol))
                    .type(EvidenceKind.BYTECODE)
                    .path(relativePath)
                    .symbol(symbol)
                    .hash(Integer.toHexString(relativePath.hashCode()))
                    .attrs(Map.of("class_file", true, "module", context.module()))
                    .build();
        }
    }

    private String ensureModuleSymbol(ExtractionContext context, ExtractionSink sink) {
        String moduleSymbol = SymbolIdFactory.module(context.module());
        String rootPath = context.rootPathString();

        EvidenceFact evidence = EvidenceFact.builder()
                .id(EvidenceIdGenerator.generate(EvidenceKind.BYTECODE, rootPath, null, moduleSymbol))
                .type(EvidenceKind.BYTECODE)
                .path(rootPath)
                .symbol(moduleSymbol)
                .attrs(Map.of("module", context.module(), "bytecode_root", rootPath))
                .build();
        sink.addEvidence(evidence);
        sink.addSymbol(SymbolFact.builder()
                .symbol(moduleSymbol)
                .kind(SymbolFactKind.MODULE)
                .name(context.module())
                .qualifiedName(context.module())
                .module(context.module())
                .bytecodeRoot(context.bytecodeRootString())
                .origin(SymbolOriginKind.BYTECODE)
                .evidenceIds(List.of(evidence.id()))
                .build());
        return moduleSymbol;
    }

    private String ensurePackageSymbol(
            ExtractionContext context,
            String moduleSymbol,
            String packageName,
            String relativePath,
            ExtractionSink sink
    ) {
        String packageSymbol = SymbolIdFactory.packageSymbol(packageName);
        EvidenceFact packageEvidence = EvidenceFact.builder()
                .id(EvidenceIdGenerator.generate(EvidenceKind.BYTECODE, relativePath, null, packageSymbol))
                .type(EvidenceKind.BYTECODE)
                .path(relativePath)
                .symbol(packageSymbol)
                .attrs(Map.of("package", packageName, "module", context.module()))
                .build();
        sink.addEvidence(packageEvidence);
        sink.addSymbol(SymbolFact.builder()
                .symbol(packageSymbol)
                .kind(SymbolFactKind.PACKAGE)
                .name(packageName)
                .qualifiedName(packageName)
                .module(context.module())
                .bytecodeRoot(context.bytecodeRootString())
                .origin(SymbolOriginKind.BYTECODE)
                .evidenceIds(List.of(packageEvidence.id()))
                .build());
        sink.addRelation(RelationFact.builder()
                .kind(RelationKind.CONTAINS)
                .srcSymbol(moduleSymbol)
                .dstSymbol(packageSymbol)
                .evidenceIds(List.of(packageEvidence.id()))
                .resolution(RelationResolutionFactory.resolved())
                .origin(FactOriginKind.BYTECODE)
                .build());
        return packageSymbol;
    }

    private SignatureFact methodSignature(String descriptor, String[] exceptions) {
        Type methodType = Type.getMethodType(descriptor);
        List<TypeRef> params = new ArrayList<>();
        for (Type argumentType : methodType.getArgumentTypes()) {
            params.add(typeRefFromAsmType(argumentType));
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

    private TypeRef descriptorToTypeRef(String descriptor) {
        return typeRefFromAsmType(Type.getType(descriptor));
    }

    private TypeRef objectTypeRef(String internalName) {
        return TypeRefFactory.simple(internalNameToQualified(internalName));
    }

    private TypeRef typeRefFromAsmType(Type type) {
        if (type.getSort() == Type.VOID) {
            return TypeRef.builder().raw("void").primitive(true).arrayDim(0).unresolved(false).build();
        }
        if (type.getSort() == Type.BOOLEAN || type.getSort() == Type.CHAR || type.getSort() == Type.BYTE
                || type.getSort() == Type.SHORT || type.getSort() == Type.INT || type.getSort() == Type.FLOAT
                || type.getSort() == Type.LONG || type.getSort() == Type.DOUBLE) {
            return TypeRef.builder().raw(type.getClassName()).primitive(true).arrayDim(0).unresolved(false).build();
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
        return AccessLevel.PACKAGE;
    }

    private Set<ModifierKind> modifierKinds(int access) {
        EnumSet<ModifierKind> set = EnumSet.noneOf(ModifierKind.class);
        if ((access & Opcodes.ACC_STATIC) != 0) set.add(ModifierKind.STATIC);
        if ((access & Opcodes.ACC_FINAL) != 0) set.add(ModifierKind.FINAL);
        if ((access & Opcodes.ACC_ABSTRACT) != 0) set.add(ModifierKind.ABSTRACT);
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) set.add(ModifierKind.SYNCHRONIZED);
        if ((access & Opcodes.ACC_NATIVE) != 0) set.add(ModifierKind.NATIVE);
        if ((access & Opcodes.ACC_STRICT) != 0) set.add(ModifierKind.STRICTFP);
        if ((access & Opcodes.ACC_TRANSIENT) != 0) set.add(ModifierKind.TRANSIENT);
        if ((access & Opcodes.ACC_VOLATILE) != 0) set.add(ModifierKind.VOLATILE);
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
        String qualified = descriptorToTypeRef(descriptor).raw();
        return qualified.endsWith("Inject")
                || qualified.endsWith("Autowired")
                || qualified.endsWith("Resource")
                || qualified.endsWith("Qualifier");
    }

    private boolean isBeanAnnotation(String descriptor) {
        String qualified = descriptorToTypeRef(descriptor).raw();
        return qualified.endsWith("Bean") || qualified.endsWith("Produces");
    }

    private boolean isProviderTypeAnnotation(String descriptor) {
        String qualified = descriptorToTypeRef(descriptor).raw();
        return qualified.endsWith("Component")
                || qualified.endsWith("Service")
                || qualified.endsWith("Repository")
                || qualified.endsWith("Controller")
                || qualified.endsWith("RestController")
                || qualified.endsWith("Named");
    }

    private boolean isEventListenerAnnotation(String descriptor) {
        String qualified = descriptorToTypeRef(descriptor).raw();
        return qualified.endsWith("EventListener") || qualified.endsWith("Subscribe");
    }

    private boolean isReflectionOwner(String owner, String methodName) {
        String qualified = internalNameToQualified(owner);
        return ("java.lang.Class".equals(qualified) && ("forName".equals(methodName) || "getMethod".equals(methodName) || "getDeclaredMethod".equals(methodName)))
                || ("java.lang.reflect.Method".equals(qualified) && "invoke".equals(methodName))
                || ("java.lang.reflect.Constructor".equals(qualified) && "newInstance".equals(methodName));
    }
}

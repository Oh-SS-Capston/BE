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

    private static final class AnnotationData {
        private final String descriptor;
        private final String qualifiedName;
        private final boolean runtimeVisible;
        private final Map<String, Object> values = new LinkedHashMap<>();

        private AnnotationData(
                String descriptor,
                String qualifiedName,
                boolean runtimeVisible
        ) {
            this.descriptor = descriptor;
            this.qualifiedName = qualifiedName;
            this.runtimeVisible = runtimeVisible;
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
            addAnnotationRelation(
                    typeSymbol,
                    descriptor,
                    visible,
                    typeEvidenceId,
                    sink
            );

            AnnotationData annotation = new AnnotationData(
                    descriptor,
                    annotationQualifiedName(descriptor),
                    visible
            );
            typeAnnotations.add(annotation);

            return annotationVisitor(annotation.values);
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
                @Override
                public AnnotationVisitor visitAnnotation(
                        String annotationDescriptor,
                        boolean visible
                ) {
                    addAnnotationRelation(
                            fieldSymbol,
                            annotationDescriptor,
                            visible,
                            evidence.id(),
                            sink
                    );

                    if (context.includeObservations()
                            && isInjectionAnnotation(annotationDescriptor)) {
                        sink.addObservation(ObservationFact.builder()
                                .kind(ObservationKind.DI_INJECTION_SITE)
                                .siteSymbol(fieldSymbol)
                                .targetTypeRef(fieldTypeRef)
                                .evidenceIds(List.of(evidence.id()))
                                .origin(FactOriginKind.OBSERVED)
                                .confidenceHint(ConfidenceHints.observation(
                                        List.of(EvidenceType.BYTECODE)
                                ))
                                .note("field injection from bytecode annotation")
                                .attrs(Map.of(
                                        "annotation",
                                        annotationQualifiedName(annotationDescriptor)
                                ))
                                .build());
                    }

                    return new AnnotationVisitor(ASM_API) {
                    };
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

                private final List<AnnotationData> methodAnnotations = new ArrayList<>();

                @Override
                public AnnotationVisitor visitAnnotation(
                        String annotationDescriptor,
                        boolean visible
                ) {
                    addAnnotationRelation(
                            symbol,
                            annotationDescriptor,
                            visible,
                            evidence.id(),
                            sink
                    );

                    AnnotationData annotation = new AnnotationData(
                            annotationDescriptor,
                            annotationQualifiedName(annotationDescriptor),
                            visible
                    );
                    methodAnnotations.add(annotation);

                    return annotationVisitor(annotation.values);
                }

                @Override
                public void visitMethodInsn(
                        int opcode,
                        String owner,
                        String methodName,
                        String methodDescriptor,
                        boolean isInterface
                ) {
                    Type methodType = Type.getMethodType(methodDescriptor);
                    SignatureFact calleeSignature = SignatureFact.builder()
                            .params(Arrays.stream(methodType.getArgumentTypes())
                                    .map(type -> ParamFact.builder()
                                            .name(null)
                                            .typeRef(typeRefFromAsmType(type))
                                            .build())
                                    .toList())
                            .build();

                    String dstSymbol = "<init>".equals(methodName)
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
                            .evidenceIds(List.of(evidence.id()))
                            .resolution(RelationResolutionFactory.resolved())
                            .origin(FactOriginKind.BYTECODE)
                            .confidenceHint(ConfidenceHints.relation(
                                    ResolutionStatus.RESOLVED,
                                    FactOriginKind.BYTECODE
                            ))
                            .build());

                    if (context.includeObservations()
                            && isReflectionOwner(owner, methodName)) {
                        sink.addObservation(ObservationFact.builder()
                                .kind(ObservationKind.REFLECTION_SITE)
                                .siteSymbol(symbol)
                                .evidenceIds(List.of(evidence.id()))
                                .origin(FactOriginKind.OBSERVED)
                                .confidenceHint(ConfidenceHints.observation(
                                        List.of(EvidenceType.BYTECODE)
                                ))
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
                public void visitFieldInsn(
                        int opcode,
                        String owner,
                        String fieldName,
                        String fieldDescriptor
                ) {
                    sink.addRelation(RelationFact.builder()
                            .kind(RelationKind.ACCESSES_FIELD)
                            .srcSymbol(symbol)
                            .dstSymbol(SymbolIdFactory.field(
                                    internalNameToQualified(owner),
                                    fieldName
                            ))
                            .evidenceIds(List.of(evidence.id()))
                            .resolution(RelationResolutionFactory.resolved())
                            .origin(FactOriginKind.BYTECODE)
                            .confidenceHint(ConfidenceHints.relation(
                                    ResolutionStatus.RESOLVED,
                                    FactOriginKind.BYTECODE
                            ))
                            .build());
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
                                evidence.id(),
                                sink
                        );
                    }
                    super.visitEnd();
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
                        typeEvidenceId,
                        sink
                );
            }
            super.visitEnd();
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
            String evidenceId,
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
                    .evidenceIds(List.of(evidenceId))
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
                .evidenceIds(List.of(evidenceId))
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
            String evidenceId,
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
                    .evidenceIds(List.of(evidenceId))
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
                    .evidenceIds(List.of(evidenceId))
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

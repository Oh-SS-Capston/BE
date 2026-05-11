package com.example.ossdoc.domain.extraction.service.extractor;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionSink;
import com.example.ossdoc.domain.extraction.dto.context.ExtractionContext;

import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.dto.model.ObservationFact;
import com.example.ossdoc.domain.extraction.dto.model.ParamFact;
import com.example.ossdoc.domain.extraction.dto.model.RelationFact;
import com.example.ossdoc.domain.extraction.dto.model.SignatureFact;
import com.example.ossdoc.domain.extraction.dto.model.StateMutation;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.dto.model.TypeRef;
import com.example.ossdoc.domain.extraction.enums.AccessLevel;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import com.example.ossdoc.domain.extraction.enums.FactOriginKind;
import com.example.ossdoc.domain.extraction.enums.Modifier;
import com.example.ossdoc.domain.extraction.enums.MutationKind;
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
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
// com.github.javaparser.ast.Modifier is used via FQN to avoid clash with extraction Modifier enum
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.ast.type.TypeParameter;
import com.example.ossdoc.domain.extraction.dto.model.TypeParam;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.modifiers.NodeWithAccessModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JavaParser + Symbol Solver 기반 AST extractor.
 *
 * 새 구조에서는 planner가 확정한 .java 파일 목록만 처리하고,
 * JavaParser 인스턴스를 chunk별로 생성해 병렬 worker 간 static 설정 충돌을 피한다.
 */
@Component
public class JavaParserAstFactsExtractor implements FactsExtractor {

    @Value("${extractor.method.max-body-lines:300}")
    private int maxBodyLines;

    private static final Set<String> MUTATING_EXACT = Set.of(
            "save", "update", "delete", "persist", "merge", "flush", "commit"
    );
    private static final Pattern MUTATING_PREFIX = Pattern.compile("^(set|add|remove)[A-Z].*");

    private record ThrowAnalysis(List<TypeRef> uncheckedTypes, boolean hasConditional) {}

    @Override
    public ChunkKind supports() {
        return ChunkKind.AST;
    }

    @Override
    public ChunkResult extract(ExtractionContext context) {
        ExtractionSink sink = new ExtractionSink();

        if (!context.isAstChunk()) {
            sink.addError("AST extractor received non-AST chunk: " + context.chunkKind());
            return sink.toChunkResult(context.chunk());
        }

        if (!context.hasFiles()) {
            sink.addWarning("AST chunk contains no files: " + context.chunkId());
            return sink.toChunkResult(context.chunk());
        }

        ParserConfiguration parserConfiguration = parserConfiguration(context, sink);
        JavaParser parser = new JavaParser(parserConfiguration);

        for (Path javaFile : context.files()) {
            if (javaFile == null || !Files.exists(javaFile) || !Files.isRegularFile(javaFile)) {
                sink.addWarning("source file does not exist or is not a regular file: " + javaFile);
                sink.recordFileSkipped();
                continue;
            }
            if (!javaFile.toString().endsWith(".java")) {
                sink.recordFileSkipped();
                continue;
            }
            parseFile(context, parser, javaFile, sink);
        }

        return sink.toChunkResult(context.chunk());
    }

    private ParserConfiguration parserConfiguration(ExtractionContext context, ExtractionSink sink) {
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(false)
                .setAttributeComments(false);

        try {
            TypeSolver typeSolver = buildTypeSolver(context, sink);
            configuration.setSymbolResolver(new JavaSymbolSolver(typeSolver));
        } catch (Exception e) {
            sink.addWarning("symbol solver initialization failed; unresolved refs may increase (" + e.getMessage() + ")");
        }
        return configuration;
    }

    private TypeSolver buildTypeSolver(ExtractionContext context, ExtractionSink sink) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(false));

        for (Path sourceRoot : context.astLookupRoots()) {
            if (sourceRoot != null && Files.isDirectory(sourceRoot)) {
                try {
                    solver.add(new JavaParserTypeSolver(sourceRoot));
                } catch (Exception e) {
                    sink.addWarning("failed to add source root to symbol solver: "
                            + sourceRoot + " (" + e.getMessage() + ")");
                }
            }
        }

        for (Path classpathEntry : context.classpathEntries()) {
            if (classpathEntry == null || !Files.exists(classpathEntry)) {
                continue;
            }

            try {
                if (Files.isRegularFile(classpathEntry)
                        && classpathEntry.getFileName() != null
                        && classpathEntry.getFileName().toString().endsWith(".jar")) {
                    solver.add(new JarTypeSolver(classpathEntry.toString()));
                }
            } catch (IOException e) {
                sink.addWarning("failed to attach classpath entry to symbol solver: "
                        + classpathEntry + " (" + e.getMessage() + ")");
            }
        }

        return solver;
    }

    private void parseFile(ExtractionContext context, JavaParser parser, Path javaFile, ExtractionSink sink) {
        String relativePath = RepoPathUtils.toRepoRelative(context.repoRoot(), javaFile);
        sink.recordFileScanned();

        try {
            ParseResult<CompilationUnit> parseResult = parser.parse(javaFile);
            if (parseResult.getResult().isEmpty()) {
                String problems = parseResult.getProblems().stream()
                        .map(problem -> problem.getVerboseMessage())
                        .collect(Collectors.joining(" | "));
                sink.addError("failed to parse source file: " + relativePath + " (" + problems + ")");
                return;
            }

            sink.recordFileParsed();
            processCompilationUnit(context, javaFile, relativePath, parseResult.getResult().orElseThrow(), sink);
        } catch (ParseProblemException | IOException e) {
            sink.addError("failed to parse source file: " + relativePath + " (" + e.getMessage() + ")");
        }
    }

    private void processCompilationUnit(
            ExtractionContext context,
            Path javaFile,
            String relativePath,
            CompilationUnit cu,
            ExtractionSink sink
    ) {
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .filter(name -> !name.isBlank())
                .orElse("(default)");

        String moduleSymbol = ensureModuleSymbol(context, sink);
        String packageSymbol = ensurePackageSymbol(context, packageName, relativePath, cu, moduleSymbol, sink, javaFile);

        for (TypeDeclaration<?> typeDeclaration : cu.getTypes()) {
            collectTypeRecursive(context, javaFile, relativePath, packageSymbol, null, typeDeclaration, sink);
        }
    }

    private void collectTypeRecursive(
            ExtractionContext context,
            Path javaFile,
            String relativePath,
            String packageSymbol,
            String nestedOwnerSymbol,
            TypeDeclaration<?> typeDeclaration,
            ExtractionSink sink
    ) {
        String qualifiedName = resolveQualifiedTypeName(typeDeclaration);
        String typeSymbol = SymbolIdFactory.type(qualifiedName);
        EvidenceFact evidence = buildAstEvidence(relativePath, javaFile, typeDeclaration, typeSymbol, EvidenceType.AST);
        sink.addEvidence(evidence);

        TypeKind typeKind = typeKind(typeDeclaration);
        String javadocText = extractDocComment(typeDeclaration);
        boolean isSealed = typeDeclaration instanceof ClassOrInterfaceDeclaration coid &&
                coid.getModifiers().stream()
                        .anyMatch(m -> "SEALED".equals(m.getKeyword().name()));
        SymbolFact typeFact = SymbolFact.builder()
                .symbol(typeSymbol)
                .kind(SymbolKind.TYPE)
                .typeKind(typeKind)
                .name(typeDeclaration.getNameAsString())
                .qualifiedName(qualifiedName)
                .packageSymbol(packageSymbol)
                .module(context.module())
                .sourceRoot(context.sourceRootString())
                .nestedIn(nestedOwnerSymbol)
                .access(accessLevel(typeDeclaration))
                .modifiers(modifierKinds(typeDeclaration.getModifiers()))
                .origin(SymbolOriginKind.AST)
                .annotations(annotationTypeRefs(typeDeclaration.getAnnotations(), sink))
                .evidenceIds(List.of(evidence.id()))
                .attrs(typeAttributes(typeDeclaration))
                .superTypeRef(superTypeRef(typeDeclaration, sink))
                .interfaceTypeRefs(interfaceTypeRefs(typeDeclaration, sink))
                .sourceFile(relativePath)
                .signature(SignatureFact.builder()
                        .javadoc(javadocText)
                        .sealed(isSealed ? Boolean.TRUE : null)
                        .build())
                .docComment(javadocText)
                .typeParams(extractTypeParams(typeDeclaration, sink))
                .build();
        sink.addSymbol(typeFact);

        addTypeObservationsIfNeeded(context, typeDeclaration, typeSymbol, evidence.id(), sink);

        for (BodyDeclaration<?> member : typeDeclaration.getMembers()) {
            if (member instanceof FieldDeclaration fieldDeclaration) {
                collectFieldDeclarations(context, javaFile, relativePath, typeSymbol, fieldDeclaration, sink);
            } else if (member instanceof ConstructorDeclaration constructorDeclaration) {
                collectConstructor(context, javaFile, relativePath, typeSymbol, constructorDeclaration, sink);
            } else if (member instanceof MethodDeclaration methodDeclaration) {
                collectMethod(context, javaFile, relativePath, typeSymbol, methodDeclaration, sink);
            } else if (member instanceof TypeDeclaration<?> nestedType) {
                collectTypeRecursive(context, javaFile, relativePath, packageSymbol, typeSymbol, nestedType, sink);
            }
        }

        if (typeDeclaration instanceof EnumDeclaration enumDeclaration) {
            for (EnumConstantDeclaration constant : enumDeclaration.getEntries()) {
                collectEnumConstant(context, javaFile, relativePath, typeSymbol, constant, sink);
            }
        }

        if (typeDeclaration instanceof RecordDeclaration recordDeclaration) {
            for (Parameter parameter : recordDeclaration.getParameters()) {
                collectRecordComponent(context, javaFile, relativePath, typeSymbol, parameter, sink);
            }
        }
    }

    private void collectFieldDeclarations(
            ExtractionContext context,
            Path javaFile,
            String relativePath,
            String ownerTypeSymbol,
            FieldDeclaration fieldDeclaration,
            ExtractionSink sink
    ) {
        for (com.github.javaparser.ast.body.VariableDeclarator variable : fieldDeclaration.getVariables()) {
            String fieldSymbol = SymbolIdFactory.field(ownerTypeSymbol.substring("type:".length()), variable.getNameAsString());
            EvidenceFact evidence = buildAstEvidence(relativePath, javaFile, variable, fieldSymbol, EvidenceType.AST);
            sink.addEvidence(evidence);

            TypeRef fieldTypeRef = toTypeRef(variable.getType(), sink);
            SignatureFact signature = SignatureFact.builder()
                    .fieldType(fieldTypeRef)
                    .build();

            SymbolFact fieldFact = SymbolFact.builder()
                    .symbol(fieldSymbol)
                    .kind(SymbolKind.FIELD)
                    .name(variable.getNameAsString())
                    .ownerSymbol(ownerTypeSymbol)
                    .module(context.module())
                    .sourceRoot(context.sourceRootString())
                    .access(accessLevel(fieldDeclaration))
                    .modifiers(modifierKinds(fieldDeclaration.getModifiers()))
                    .origin(SymbolOriginKind.AST)
                    .annotations(annotationTypeRefs(fieldDeclaration.getAnnotations(), sink))
                    .evidenceIds(List.of(evidence.id()))
                    .signature(signature)
                    .sourceFile(relativePath)
                    .docComment(extractDocComment(fieldDeclaration))
                    .build();
            sink.addSymbol(fieldFact);

            addFieldObservationsIfNeeded(context, fieldDeclaration, fieldSymbol, fieldTypeRef, evidence.id(), sink);
        }
    }

    private void collectConstructor(
            ExtractionContext context,
            Path javaFile,
            String relativePath,
            String ownerTypeSymbol,
            ConstructorDeclaration declaration,
            ExtractionSink sink
    ) {
        String javadocText = extractDocComment(declaration);
        SignatureFact signature = callableSignature(declaration, javadocText, sink);
        String ownerQualifiedName = ownerTypeSymbol.substring("type:".length());
        String constructorSymbol = SymbolIdFactory.constructor(ownerQualifiedName, signature);
        EvidenceFact evidence = buildAstEvidence(relativePath, javaFile, declaration, constructorSymbol, EvidenceType.AST);
        sink.addEvidence(evidence);

        SymbolFact fact = SymbolFact.builder()
                .symbol(constructorSymbol)
                .kind(SymbolKind.CONSTRUCTOR)
                .name(declaration.getNameAsString())
                .ownerSymbol(ownerTypeSymbol)
                .module(context.module())
                .sourceRoot(context.sourceRootString())
                .access(accessLevel(declaration))
                .modifiers(modifierKinds(declaration.getModifiers()))
                .origin(SymbolOriginKind.AST)
                .annotations(annotationTypeRefs(declaration.getAnnotations(), sink))
                .evidenceIds(List.of(evidence.id()))
                .signature(signature)
                .sourceFile(relativePath)
                .docComment(javadocText)
                .build();
        sink.addSymbol(fact);

        addCallableBodyRelations(declaration, constructorSymbol, evidence.id(), sink);
        addConstructorObservationsIfNeeded(context, declaration, constructorSymbol, evidence.id(), sink);
    }

    private void collectMethod(
            ExtractionContext context,
            Path javaFile,
            String relativePath,
            String ownerTypeSymbol,
            MethodDeclaration declaration,
            ExtractionSink sink
    ) {
        String javadocText = extractDocComment(declaration);
        SignatureFact signature = callableSignature(declaration, javadocText, sink);
        String ownerQualifiedName = ownerTypeSymbol.substring("type:".length());
        String methodSymbol = SymbolIdFactory.method(ownerQualifiedName, declaration.getNameAsString(), signature);
        EvidenceFact evidence = buildAstEvidence(relativePath, javaFile, declaration, methodSymbol, EvidenceType.AST);
        sink.addEvidence(evidence);

        ThrowAnalysis throwAnalysis = analyzeThrows(declaration, sink);
        List<StateMutation> mutations = analyzeMutations(declaration);

        SymbolFact fact = SymbolFact.builder()
                .symbol(methodSymbol)
                .kind(SymbolKind.METHOD)
                .name(declaration.getNameAsString())
                .ownerSymbol(ownerTypeSymbol)
                .module(context.module())
                .sourceRoot(context.sourceRootString())
                .access(accessLevel(declaration))
                .modifiers(modifierKinds(declaration.getModifiers()))
                .origin(SymbolOriginKind.AST)
                .annotations(annotationTypeRefs(declaration.getAnnotations(), sink))
                .evidenceIds(List.of(evidence.id()))
                .signature(signature)
                .sourceFile(relativePath)
                .docComment(javadocText)
                .throwsUnchecked(throwAnalysis.uncheckedTypes())
                .hasConditionalThrow(throwAnalysis.hasConditional() ? Boolean.TRUE : null)
                .stateMutations(mutations)
                .build();
        sink.addSymbol(fact);

        addCallableBodyRelations(declaration, methodSymbol, evidence.id(), sink);
        addMethodObservationsIfNeeded(context, declaration, methodSymbol, evidence.id(), sink);
    }

    private void collectEnumConstant(
            ExtractionContext context,
            Path javaFile,
            String relativePath,
            String ownerTypeSymbol,
            EnumConstantDeclaration constant,
            ExtractionSink sink
    ) {
        String fieldSymbol = SymbolIdFactory.field(ownerTypeSymbol.substring("type:".length()), constant.getNameAsString());
        EvidenceFact evidence = buildAstEvidence(relativePath, javaFile, constant, fieldSymbol, EvidenceType.AST);
        sink.addEvidence(evidence);

        SymbolFact fact = SymbolFact.builder()
                .symbol(fieldSymbol)
                .kind(SymbolKind.FIELD)
                .name(constant.getNameAsString())
                .ownerSymbol(ownerTypeSymbol)
                .module(context.module())
                .sourceRoot(context.sourceRootString())
                .access(AccessLevel.PUBLIC)
                .modifiers(Set.of(Modifier.STATIC, Modifier.FINAL))
                .origin(SymbolOriginKind.AST)
                .evidenceIds(List.of(evidence.id()))
                .signature(SignatureFact.builder()
                        .fieldType(TypeRefFactory.simple(ownerTypeSymbol.substring("type:".length())))
                        .build())
                .sourceFile(relativePath)
                .build();
        sink.addSymbol(fact);
    }

    private void collectRecordComponent(
            ExtractionContext context,
            Path javaFile,
            String relativePath,
            String ownerTypeSymbol,
            Parameter parameter,
            ExtractionSink sink
    ) {
        String fieldSymbol = SymbolIdFactory.field(ownerTypeSymbol.substring("type:".length()), parameter.getNameAsString());
        EvidenceFact evidence = buildAstEvidence(relativePath, javaFile, parameter, fieldSymbol, EvidenceType.AST);
        sink.addEvidence(evidence);

        TypeRef fieldTypeRef = toTypeRef(parameter.getType(), sink);
        SymbolFact fact = SymbolFact.builder()
                .symbol(fieldSymbol)
                .kind(SymbolKind.FIELD)
                .name(parameter.getNameAsString())
                .ownerSymbol(ownerTypeSymbol)
                .module(context.module())
                .sourceRoot(context.sourceRootString())
                .access(AccessLevel.PRIVATE)
                .modifiers(Set.of(Modifier.FINAL))
                .origin(SymbolOriginKind.GENERATED)
                .evidenceIds(List.of(evidence.id()))
                .signature(SignatureFact.builder().fieldType(fieldTypeRef).build())
                .sourceFile(relativePath)
                .build();
        sink.addSymbol(fact);
    }

    private void addCallableBodyRelations(CallableDeclaration<?> declaration, String callableSymbol, String evidenceId, ExtractionSink sink) {
        declaration.findAll(MethodCallExpr.class).forEach(methodCallExpr -> addMethodCallRelation(callableSymbol, methodCallExpr, evidenceId, sink));
        declaration.findAll(ObjectCreationExpr.class).forEach(objectCreationExpr -> addObjectCreationCallRelation(callableSymbol, objectCreationExpr, evidenceId, sink));
        declaration.findAll(NameExpr.class).forEach(nameExpr -> addFieldAccessRelation(callableSymbol, nameExpr, evidenceId, sink));
        declaration.findAll(FieldAccessExpr.class).forEach(fieldAccessExpr -> addFieldAccessRelation(callableSymbol, fieldAccessExpr, evidenceId, sink));
    }

    private void addMethodCallRelation(String callerSymbol, MethodCallExpr methodCallExpr, String evidenceId, ExtractionSink sink) {
        Integer callSiteLine = methodCallExpr.getBegin()
                .map(pos -> pos.line)
                .orElse(null);

        try {
            ResolvedMethodDeclaration resolved = methodCallExpr.resolve();
            String dstSymbol = methodSymbol(resolved, sink);
            sink.addRelation(RelationFact.builder()
                    .kind(RelationKind.CALLS)
                    .srcSymbol(callerSymbol)
                    .dstSymbol(dstSymbol)
                    .evidenceIds(List.of(evidenceId))
                    .resolution(RelationResolutionFactory.resolved())
                    .origin(FactOriginKind.AST)
                    .callSiteLine(callSiteLine)
                    .confidenceHint(ConfidenceHints.relation(ResolutionStatus.RESOLVED, FactOriginKind.AST))
                    .build());
        } catch (Exception e) {
            sink.addRelation(RelationFact.builder()
                    .kind(RelationKind.CALLS)
                    .srcSymbol(callerSymbol)
                    .dstRawRef(methodCallExpr.getNameAsString() + signatureHint(methodCallExpr.getArguments().size()))
                    .evidenceIds(List.of(evidenceId))
                    .resolution(RelationResolutionFactory.unresolved(e.getClass().getSimpleName()))
                    .origin(FactOriginKind.AST)
                    .callSiteLine(callSiteLine)
                    .confidenceHint(ConfidenceHints.relation(ResolutionStatus.UNRESOLVED, FactOriginKind.AST))
                    .build());
        }
    }

    private void addObjectCreationCallRelation(String callerSymbol, ObjectCreationExpr objectCreationExpr, String evidenceId, ExtractionSink sink) {
        try {
            String rawType = objectCreationExpr.getType().getNameWithScope();
            String dstRaw = "new " + rawType + signatureHint(objectCreationExpr.getArguments().size());

            // best-guess FQCN: resolve 시도, 실패 시 null
            String dstSymbol = null;
            try {
                dstSymbol = objectCreationExpr.getType().resolve().asReferenceType().getQualifiedName();
            } catch (Exception ignored) {
                // resolve 실패 → dstSymbol null, dstRawRef만 사용
            }

            Integer callSiteLine = objectCreationExpr.getBegin()
                    .map(pos -> pos.line)
                    .orElse(null);

            ResolutionStatus resStatus = dstSymbol != null
                    ? ResolutionStatus.PARTIAL : ResolutionStatus.UNRESOLVED;

            sink.addRelation(RelationFact.builder()
                    .kind(RelationKind.CALLS)
                    .srcSymbol(callerSymbol)
                    .dstSymbol(dstSymbol)
                    .dstRawRef(dstRaw)
                    .evidenceIds(List.of(evidenceId))
                    .resolution(dstSymbol != null
                            ? RelationResolutionFactory.partial("constructor resolution deferred")
                            : RelationResolutionFactory.unresolved("constructor type unresolved"))
                    .origin(FactOriginKind.AST)
                    .callSiteLine(callSiteLine)
                    .confidenceHint(ConfidenceHints.relation(resStatus, FactOriginKind.AST))
                    .build());
        } catch (Exception e) {
            sink.addWarning("failed to record constructor call relation: " + e.getMessage());
        }
    }

    private void addFieldAccessRelation(String callerSymbol, Expression expression, String evidenceId, ExtractionSink sink) {
        try {
            if (expression instanceof NameExpr nameExpr) {
                var resolved = nameExpr.resolve();
                if (!resolved.isField()) {
                    return;
                }

                ResolvedFieldDeclaration field = resolved.asField();
                sink.addRelation(RelationFact.builder()
                        .kind(RelationKind.ACCESSES_FIELD)
                        .srcSymbol(callerSymbol)
                        .dstSymbol(fieldSymbol(field))
                        .evidenceIds(List.of(evidenceId))
                        .resolution(RelationResolutionFactory.resolved())
                        .origin(FactOriginKind.AST)
                        .build());
                return;
            }

            if (expression instanceof FieldAccessExpr fieldAccessExpr) {
                var resolved = fieldAccessExpr.resolve();
                if (!resolved.isField()) {
                    return;
                }

                ResolvedFieldDeclaration field = resolved.asField();
                sink.addRelation(RelationFact.builder()
                        .kind(RelationKind.ACCESSES_FIELD)
                        .srcSymbol(callerSymbol)
                        .dstSymbol(fieldSymbol(field))
                        .evidenceIds(List.of(evidenceId))
                        .resolution(RelationResolutionFactory.resolved())
                        .origin(FactOriginKind.AST)
                        .build());
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }


    private void addTypeObservationsIfNeeded(
            ExtractionContext context,
            TypeDeclaration<?> typeDeclaration,
            String typeSymbol,
            String evidenceId,
            ExtractionSink sink
    ) {
        if (!context.includeObservations()) {
            return;
        }

        Set<String> annotationNames = nodeAnnotationNames(typeDeclaration, sink);
        if (annotationNames.stream().anyMatch(this::isDiProviderTypeAnnotation)) {
            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.DI_PROVIDER)
                    .siteSymbol(typeSymbol)
                    .evidenceIds(List.of(evidenceId))
                    .origin(FactOriginKind.OBSERVED)
                    .confidenceHint(ConfidenceHints.observation(List.of(EvidenceType.AST)))
                    .note("type-level DI provider annotation")
                    .attrs(Map.of("annotations", annotationNames))
                    .build());
        }

        if (annotationNames.stream().anyMatch(this::isConfigurationAnnotation)) {
            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.CONFIG_WIRING)
                    .siteSymbol(typeSymbol)
                    .evidenceIds(List.of(evidenceId))
                    .origin(FactOriginKind.OBSERVED)
                    .confidenceHint(ConfidenceHints.observation(List.of(EvidenceType.AST)))
                    .note("configuration class annotation")
                    .attrs(Map.of("annotations", annotationNames))
                    .build());
        }
    }

    private void addFieldObservationsIfNeeded(
            ExtractionContext context,
            FieldDeclaration fieldDeclaration,
            String fieldSymbol,
            TypeRef fieldTypeRef,
            String evidenceId,
            ExtractionSink sink
    ) {
        if (!context.includeObservations()) {
            return;
        }

        Set<String> annotationNames = nodeAnnotationNames(fieldDeclaration, sink);
        if (annotationNames.stream().anyMatch(this::isInjectionAnnotation)) {
            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.DI_INJECTION_SITE)
                    .siteSymbol(fieldSymbol)
                    .targetTypeRef(fieldTypeRef)
                    .evidenceIds(List.of(evidenceId))
                    .origin(FactOriginKind.OBSERVED)
                    .confidenceHint(ConfidenceHints.observation(List.of(EvidenceType.AST)))
                    .note("field injection")
                    .attrs(Map.of("annotations", annotationNames))
                    .build());
        }
    }

    private void addConstructorObservationsIfNeeded(
            ExtractionContext context,
            ConstructorDeclaration declaration,
            String constructorSymbol,
            String evidenceId,
            ExtractionSink sink
    ) {
        if (!context.includeObservations()) {
            return;
        }

        boolean hasInjection = nodeAnnotationNames(declaration, sink).stream().anyMatch(this::isInjectionAnnotation);
        if (!hasInjection) {
            return;
        }

        for (Parameter parameter : declaration.getParameters()) {
            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.DI_INJECTION_SITE)
                    .siteSymbol(constructorSymbol)
                    .targetTypeRef(toTypeRef(parameter.getType(), sink))
                    .evidenceIds(List.of(evidenceId))
                    .origin(FactOriginKind.OBSERVED)
                    .confidenceHint(ConfidenceHints.observation(List.of(EvidenceType.AST)))
                    .note("constructor injection parameter")
                    .attrs(Map.of("parameter", parameter.getNameAsString()))
                    .build());
        }
    }

    private void addMethodObservationsIfNeeded(
            ExtractionContext context,
            MethodDeclaration declaration,
            String methodSymbol,
            String evidenceId,
            ExtractionSink sink
    ) {
        if (!context.includeObservations()) {
            return;
        }

        Set<String> annotationNames = nodeAnnotationNames(declaration, sink);
        if (annotationNames.stream().anyMatch(this::isBeanAnnotation)) {
            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.DI_PROVIDER)
                    .siteSymbol(methodSymbol)
                    .targetTypeRef(declaration.getType().isVoidType() ? null : toTypeRef(declaration.getType(), sink))
                    .evidenceIds(List.of(evidenceId))
                    .origin(FactOriginKind.OBSERVED)
                    .confidenceHint(ConfidenceHints.observation(List.of(EvidenceType.AST)))
                    .note("@Bean style provider method")
                    .attrs(Map.of("annotations", annotationNames))
                    .build());
        }

        if (annotationNames.stream().anyMatch(this::isEventSubscriberAnnotation)) {
            sink.addObservation(ObservationFact.builder()
                    .kind(ObservationKind.EVENT_SUBSCRIPTION)
                    .siteSymbol(methodSymbol)
                    .targetTypeRef(firstParameterType(declaration, sink))
                    .evidenceIds(List.of(evidenceId))
                    .origin(FactOriginKind.OBSERVED)
                    .confidenceHint(ConfidenceHints.observation(List.of(EvidenceType.AST)))
                    .note("event subscriber method")
                    .attrs(Map.of("annotations", annotationNames))
                    .build());
        }

        declaration.findAll(MethodCallExpr.class).forEach(call -> {
            if (isPublishEventCall(call)) {
                sink.addObservation(ObservationFact.builder()
                        .kind(ObservationKind.EVENT_PUBLICATION)
                        .siteSymbol(methodSymbol)
                        .targetTypeRef(firstArgumentType(call, sink))
                        .evidenceIds(List.of(evidenceId))
                        .origin(FactOriginKind.OBSERVED)
                        .confidenceHint(ConfidenceHints.observation(List.of(EvidenceType.AST)))
                        .note("event publish candidate")
                        .attrs(Map.of("method", call.getNameAsString()))
                        .build());
            }

            if (isReflectionCall(call)) {
                sink.addObservation(ObservationFact.builder()
                        .kind(ObservationKind.REFLECTION_SITE)
                        .siteSymbol(methodSymbol)
                        .evidenceIds(List.of(evidenceId))
                        .origin(FactOriginKind.OBSERVED)
                        .confidenceHint(ConfidenceHints.observation(List.of(EvidenceType.AST)))
                        .note("reflection API usage")
                        .attrs(Map.of(
                                "method", call.getNameAsString(),
                                "scope", call.getScope().map(Expression::toString).orElse("")
                        ))
                        .build());
            }
        });
    }

    private String ensureModuleSymbol(ExtractionContext context, ExtractionSink sink) {
        return SymbolIdFactory.module(context.module());
    }

    private String ensurePackageSymbol(
            ExtractionContext context,
            String packageName,
            String relativePath,
            CompilationUnit cu,
            String moduleSymbol,
            ExtractionSink sink,
            Path javaFile
    ) {
        return SymbolIdFactory.packageSymbol(packageName);
    }

    private SignatureFact callableSignature(CallableDeclaration<?> declaration, String javadoc, ExtractionSink sink) {
        List<ParamFact> params = declaration.getParameters().stream()
                .map(p -> ParamFact.builder()
                        .name(p.getNameAsString())
                        .typeRef(toTypeRef(p.getType(), sink))
                        .build())
                .toList();

        TypeRef returns = declaration instanceof MethodDeclaration methodDeclaration
                ? toTypeRef(methodDeclaration.getType(), sink)
                : null;

        List<TypeRef> throwsTypes = declaration.getThrownExceptions().stream()
                .map(type -> toTypeRef(type, sink))
                .toList();

        return SignatureFact.builder()
                .params(params)
                .returns(returns)
                .throwsTypes(throwsTypes)
                .javadoc(javadoc)
                .build();
    }

    private TypeRef superTypeRef(TypeDeclaration<?> typeDeclaration, ExtractionSink sink) {
        if (typeDeclaration instanceof ClassOrInterfaceDeclaration classOrInterface && !classOrInterface.getExtendedTypes().isEmpty()) {
            return toTypeRef(classOrInterface.getExtendedTypes().get(0), sink);
        }
        return null;
    }

    private List<TypeRef> interfaceTypeRefs(TypeDeclaration<?> typeDeclaration, ExtractionSink sink) {
        if (typeDeclaration instanceof ClassOrInterfaceDeclaration classOrInterface) {
            return classOrInterface.getImplementedTypes().stream().map(type -> toTypeRef(type, sink)).toList();
        }
        if (typeDeclaration instanceof EnumDeclaration enumDeclaration) {
            return enumDeclaration.getImplementedTypes().stream().map(type -> toTypeRef(type, sink)).toList();
        }
        if (typeDeclaration instanceof RecordDeclaration recordDeclaration) {
            return recordDeclaration.getImplementedTypes().stream().map(type -> toTypeRef(type, sink)).toList();
        }
        return List.of();
    }

    private Map<String, Object> typeAttributes(TypeDeclaration<?> typeDeclaration) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (typeDeclaration instanceof RecordDeclaration recordDeclaration) {
            attrs.put("record_components", recordDeclaration.getParameters().stream().map(Parameter::getNameAsString).toList());
        }
        return attrs.isEmpty() ? null : attrs;
    }

    private List<TypeRef> annotationTypeRefs(List<AnnotationExpr> annotations, ExtractionSink sink) {
        if (annotations == null || annotations.isEmpty()) {
            return List.of();
        }
        return annotations.stream().map(annotation -> toAnnotationTypeRef(annotation, sink)).toList();
    }

    private Set<String> nodeAnnotationNames(NodeWithAnnotations<?> node, ExtractionSink sink) {
        return node.getAnnotations().stream()
                .map(annotation -> toAnnotationTypeRef(annotation, sink).raw())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private TypeRef toAnnotationTypeRef(AnnotationExpr annotationExpr, ExtractionSink sink) {
        sink.recordTotalTypeRef();
        try {
            return TypeRefFactory.simple(annotationExpr.resolve().getQualifiedName());
        } catch (Exception e) {
            sink.recordUnresolvedTypeRef();
            return TypeRefFactory.unresolved(annotationExpr.getNameAsString(), annotationExpr.getNameAsString());
        }
    }

    private TypeRef toTypeRef(Type type, ExtractionSink sink) {
        if (type == null) {
            return null;
        }

        sink.recordTotalTypeRef();
        try {
            ResolvedType resolvedType = type.resolve();
            TypeRef result = toTypeRef(resolvedType, type.asString(), sink);
            if (Boolean.TRUE.equals(result.unresolved())) {
                sink.recordUnresolvedTypeRef();
            }
            return result;
        } catch (Exception e) {
            sink.recordUnresolvedTypeRef();
            return fallbackTypeRefFromAst(type);
        }
    }

    private TypeRef toTypeRef(ResolvedType resolvedType, String sourceText, ExtractionSink sink) {
        if (resolvedType == null) {
            return TypeRefFactory.unresolved(sourceText, sourceText);
        }

        if (resolvedType.isPrimitive()) {
            ResolvedPrimitiveType primitiveType = resolvedType.asPrimitive();
            return TypeRef.builder()
                    .raw(primitiveType.describe())
                    .arrayDim(0)
                    .primitive(true)
                    .unresolved(false)
                    .sourceText(sourceText)
                    .build();
        }

        if (resolvedType.isArray()) {
            ResolvedArrayType arrayType = resolvedType.asArrayType();
            int dim = 0;
            ResolvedType component = arrayType;
            while (component.isArray()) {
                dim++;
                component = component.asArrayType().getComponentType();
            }
            TypeRef base = toTypeRef(component, sourceText, sink);
            return TypeRef.builder()
                    .raw(base.raw())
                    .args(base.args())
                    .arrayDim(dim)
                    .primitive(base.primitive())
                    .unresolved(base.unresolved())
                    .sourceText(sourceText)
                    .build();
        }

        if (resolvedType.isReferenceType()) {
            ResolvedReferenceType referenceType = resolvedType.asReferenceType();
            String raw = referenceType.getQualifiedName();
            List<TypeRef> args = referenceType.typeParametersValues().stream()
                    .map(arg -> toTypeRef(arg, arg.describe(), sink))
                    .toList();
            return TypeRef.builder()
                    .raw(raw)
                    .args(args)
                    .arrayDim(0)
                    .primitive(false)
                    .unresolved(false)
                    .sourceText(sourceText)
                    .build();
        }

        if (resolvedType.isVoid()) {
            return TypeRef.builder()
                    .raw("void")
                    .arrayDim(0)
                    .primitive(true)
                    .unresolved(false)
                    .sourceText(sourceText)
                    .build();
        }

        return TypeRefFactory.unresolved(resolvedType.describe(), sourceText);
    }

    private TypeRef fallbackTypeRefFromAst(Type type) {
        if (type.isPrimitiveType()) {
            PrimitiveType primitiveType = type.asPrimitiveType();
            return TypeRef.builder()
                    .raw(primitiveType.asString())
                    .arrayDim(0)
                    .primitive(true)
                    .unresolved(false)
                    .sourceText(type.asString())
                    .build();
        }

        if (type.isArrayType()) {
            com.github.javaparser.ast.type.ArrayType arrayType = type.asArrayType();
            int dim = 0;
            Type component = arrayType;
            while (component.isArrayType()) {
                dim++;
                component = component.asArrayType().getComponentType();
            }
            TypeRef base = fallbackTypeRefFromAst(component);
            return TypeRef.builder()
                    .raw(base.raw())
                    .args(base.args())
                    .arrayDim(dim)
                    .primitive(base.primitive())
                    .unresolved(true)
                    .sourceText(type.asString())
                    .build();
        }

        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classOrInterfaceType = type.asClassOrInterfaceType();
            List<TypeRef> args = classOrInterfaceType.getTypeArguments()
                    .map(items -> items.stream().map(this::fallbackTypeRefFromAst).toList())
                    .orElse(List.of());
            return TypeRef.builder()
                    .raw(classOrInterfaceType.getNameWithScope())
                    .args(args)
                    .arrayDim(0)
                    .primitive(false)
                    .unresolved(true)
                    .sourceText(type.asString())
                    .build();
        }

        return TypeRef.builder()
                .raw(type.asString())
                .arrayDim(0)
                .primitive(false)
                .unresolved(true)
                .sourceText(type.asString())
                .build();
    }

    private String resolveQualifiedTypeName(TypeDeclaration<?> typeDeclaration) {
        return typeDeclaration.getFullyQualifiedName().orElseGet(() -> {
            Optional<TypeDeclaration<?>> parentType = typeDeclaration.getParentNode()
                    .filter(TypeDeclaration.class::isInstance)
                    .map(node -> (TypeDeclaration<?>) node);

            if (parentType.isPresent()) {
                return resolveQualifiedTypeName(parentType.get()) + "$" + typeDeclaration.getNameAsString();
            }

            String packageName = typeDeclaration.findCompilationUnit()
                    .flatMap(CompilationUnit::getPackageDeclaration)
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            return packageName.isBlank()
                    ? typeDeclaration.getNameAsString()
                    : packageName + "." + typeDeclaration.getNameAsString();
        });
    }

    private String extractDocComment(NodeWithJavadoc<?> node) {
        return node.getJavadoc().map(javadoc -> {
            // 설명 본문 (블록 태그 이전)
            String desc = javadoc.getDescription().toText();
            int blankLine = desc.indexOf("\n\n");
            if (blankLine > 0) desc = desc.substring(0, blankLine);
            desc = desc.replaceAll("<[^>]+>", "").trim();

            // 블록 태그 전체 포함 — @implSpec, @apiNote 등이 publicapi 탐지에 필요
            String tags = javadoc.getBlockTags().stream()
                    .map(tag -> "@" + tag.getTagName() + " " + tag.getContent().toText().trim())
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining(" "));
            tags = tags.replaceAll("<[^>]+>", "").trim();

            String result = desc.isBlank() ? tags
                    : tags.isBlank() ? desc
                    : desc + " " + tags;
            return result.isBlank() ? null : result;
        }).orElse(null);
    }

    private List<TypeParam> extractTypeParams(TypeDeclaration<?> typeDecl, ExtractionSink sink) {
        NodeList<TypeParameter> typeParameters;
        if (typeDecl instanceof ClassOrInterfaceDeclaration cid) {
            typeParameters = cid.getTypeParameters();
        } else if (typeDecl instanceof RecordDeclaration rd) {
            typeParameters = rd.getTypeParameters();
        } else {
            return null;
        }
        if (typeParameters.isEmpty()) {
            return null;
        }
        return typeParameters.stream().map(tp -> {
            String name = tp.getNameAsString();
            List<TypeRef> bounds = tp.getTypeBound().stream()
                    .map(bound -> toTypeRef(bound, sink))
                    .toList();
            return TypeParam.builder()
                    .name(name)
                    .bounds(bounds)
                    .build();
        }).toList();
    }

    private ThrowAnalysis analyzeThrows(MethodDeclaration method, ExtractionSink sink) {
        if (method.getBody().isEmpty()) {
            return new ThrowAnalysis(null, false);
        }
        int bodyLines = method.getEnd().map(e -> e.line).orElse(0)
                      - method.getBegin().map(b -> b.line).orElse(0);
        if (bodyLines > maxBodyLines) {
            return new ThrowAnalysis(List.of(), false);
        }

        List<TypeRef> unchecked = new ArrayList<>();
        boolean[] hasConditional = {false};

        method.findAll(ThrowStmt.class).forEach(throwStmt -> {
            if (throwStmt.getExpression() instanceof ObjectCreationExpr oce) {
                unchecked.add(toTypeRef(oce.getType(), sink));
                if (isInsideConditional(throwStmt)) {
                    hasConditional[0] = true;
                }
            }
        });

        return new ThrowAnalysis(
                unchecked.isEmpty() ? null : unchecked,
                hasConditional[0]
        );
    }

    private List<StateMutation> analyzeMutations(MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return null;
        }
        int bodyLines = method.getEnd().map(e -> e.line).orElse(0)
                      - method.getBegin().map(b -> b.line).orElse(0);
        if (bodyLines > maxBodyLines) {
            return List.of();
        }

        List<StateMutation> mutations = new ArrayList<>();
        int[] seqIdx = {0};

        method.findAll(AssignExpr.class).forEach(assign -> {
            if (mutations.size() >= 20) return;
            String target = null;
            if (assign.getTarget() instanceof FieldAccessExpr fae) {
                target = fae.toString();
            } else if (assign.getTarget() instanceof NameExpr ne) {
                if (isFieldReference(ne, method)) {
                    target = "this." + ne.getNameAsString();
                }
            }
            if (target != null) {
                mutations.add(StateMutation.builder()
                        .kind(MutationKind.FIELD_WRITE)
                        .target(target)
                        .sequenceIndex(seqIdx[0]++)
                        .isConditional(isInsideConditional(assign))
                        .build());
            }
        });

        method.findAll(MethodCallExpr.class).forEach(call -> {
            if (mutations.size() >= 20) return;
            String name = call.getNameAsString();
            if (MUTATING_EXACT.contains(name) || MUTATING_PREFIX.matcher(name).matches()) {
                String targetStr = call.getScope()
                        .map(s -> s.toString() + "." + name)
                        .orElse(name);
                mutations.add(StateMutation.builder()
                        .kind(MutationKind.CALL_MUTATING)
                        .target(targetStr)
                        .sequenceIndex(seqIdx[0]++)
                        .isConditional(isInsideConditional(call))
                        .build());
            }
        });

        return mutations.isEmpty() ? null : mutations;
    }

    private boolean isInsideConditional(Node node) {
        Node parent = node.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof IfStmt || parent instanceof SwitchStmt) return true;
            if (parent instanceof MethodDeclaration || parent instanceof ConstructorDeclaration) break;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean isFieldReference(NameExpr ne, MethodDeclaration method) {
        String name = ne.getNameAsString();

        for (Parameter param : method.getParameters()) {
            if (param.getNameAsString().equals(name)) return false;
        }

        if (method.getBody().isPresent()) {
            for (VariableDeclarator vd : method.getBody().get().findAll(VariableDeclarator.class)) {
                if (vd.getNameAsString().equals(name)) return false;
            }
        }

        return true;
    }

    private TypeKind typeKind(TypeDeclaration<?> typeDeclaration) {
        if (typeDeclaration instanceof AnnotationDeclaration) {
            return TypeKind.ANNOTATION;
        }
        if (typeDeclaration instanceof EnumDeclaration) {
            return TypeKind.ENUM;
        }
        if (typeDeclaration instanceof RecordDeclaration) {
            return TypeKind.RECORD;
        }
        if (typeDeclaration instanceof ClassOrInterfaceDeclaration classOrInterface && classOrInterface.isInterface()) {
            return TypeKind.INTERFACE;
        }
        return TypeKind.CLASS;
    }

    private AccessLevel accessLevel(Node node) {
        if (node instanceof NodeWithAccessModifiers<?> accessNode) {
            return switch (accessNode.getAccessSpecifier()) {
                case PUBLIC -> AccessLevel.PUBLIC;
                case PROTECTED -> AccessLevel.PROTECTED;
                case PRIVATE -> AccessLevel.PRIVATE;
                default -> AccessLevel.PACKAGE_PRIVATE;
            };
        }
        return AccessLevel.PACKAGE_PRIVATE;
    }

    private Set<Modifier> modifierKinds(List<com.github.javaparser.ast.Modifier> modifiers) {
        EnumSet<Modifier> set = EnumSet.noneOf(Modifier.class);
        for (com.github.javaparser.ast.Modifier modifier : modifiers) {
            switch (modifier.getKeyword()) {
                case STATIC -> set.add(Modifier.STATIC);
                case FINAL -> set.add(Modifier.FINAL);
                case ABSTRACT -> set.add(Modifier.ABSTRACT);
                case SYNCHRONIZED -> set.add(Modifier.SYNCHRONIZED);
                case NATIVE -> set.add(Modifier.NATIVE);
                case STRICTFP -> set.add(Modifier.STRICTFP);
                case TRANSIENT -> set.add(Modifier.TRANSIENT);
                case VOLATILE -> set.add(Modifier.VOLATILE);
                default -> {
                }
            }
        }
        return set;
    }

    private EvidenceFact buildAstEvidence(String relativePath, Path javaFile, Node node, String symbol, EvidenceType kind) {
        Integer startLine = null, startCol = null, endLine = null, endCol = null;
        if (node.getRange().isPresent()) {
            Range range = node.getRange().get();
            startLine = range.begin.line;
            startCol = range.begin.column;
            endLine = range.end.line;
            endCol = range.end.column;
        }
        String snippet = readSnippet(javaFile, startLine, endLine);
        if (snippet != null && snippet.length() > 300) {
            snippet = snippet.substring(0, 300);
        }
        String evidenceId = EvidenceIdGenerator.generate(kind, relativePath, startLine, startCol, endLine, endCol, symbol);
        return EvidenceFact.builder()
                .id(evidenceId)
                .type(kind)
                .path(relativePath)
                .startLine(startLine)
                .endLine(endLine)
                .startCol(startCol)
                .endCol(endCol)
                .symbol(symbol)
                .snippet(snippet)
                .hash(snippet == null || snippet.isBlank() ? null : Integer.toHexString(snippet.hashCode()))
                .build();
    }

    private String readSnippet(Path javaFile, Integer startLine, Integer endLine) {
        if (startLine == null || endLine == null) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(javaFile, java.nio.charset.StandardCharsets.UTF_8);
            int start = Math.max(1, startLine);
            int end = Math.min(lines.size(), endLine);
            if (start > end) {
                return null;
            }
            return String.join("\n", lines.subList(start - 1, end));
        } catch (IOException e) {
            return null;
        }
    }

    private String methodSymbol(ResolvedMethodDeclaration resolved, ExtractionSink sink) {
        String owner = resolved.declaringType().getQualifiedName();
        List<ParamFact> params = new ArrayList<>();
        for (int i = 0; i < resolved.getNumberOfParams(); i++) {
            ResolvedParameterDeclaration parameter = resolved.getParam(i);
            params.add(ParamFact.builder()
                    .name(parameter.getName())
                    .typeRef(toTypeRef(parameter.getType(), parameter.describeType(), sink))
                    .build());
        }
        SignatureFact signatureFact = SignatureFact.builder().params(params).build();
        return SymbolIdFactory.method(owner, resolved.getName(), signatureFact);
    }

    private String fieldSymbol(ResolvedFieldDeclaration field) {
        return SymbolIdFactory.field(field.declaringType().getQualifiedName(), field.getName());
    }

    private String signatureHint(int argCount) {
        return "(" + argCount + " args)";
    }

    private boolean isInjectionAnnotation(String annotationName) {
        return annotationName.endsWith("Inject")
                || annotationName.endsWith("Autowired")
                || annotationName.endsWith("Resource")
                || annotationName.endsWith("Qualifier");
    }

    private boolean isBeanAnnotation(String annotationName) {
        return annotationName.endsWith("Bean") || annotationName.endsWith("Produces");
    }

    private boolean isDiProviderTypeAnnotation(String annotationName) {
        return annotationName.endsWith("Component")
                || annotationName.endsWith("Service")
                || annotationName.endsWith("Repository")
                || annotationName.endsWith("Controller")
                || annotationName.endsWith("RestController")
                || annotationName.endsWith("Named");
    }

    private boolean isConfigurationAnnotation(String annotationName) {
        return annotationName.endsWith("Configuration") || annotationName.endsWith("Import");
    }

    private boolean isEventSubscriberAnnotation(String annotationName) {
        return annotationName.endsWith("EventListener") || annotationName.endsWith("Subscribe");
    }

    private boolean isPublishEventCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        return "publishEvent".equals(name) || "post".equals(name);
    }

    private boolean isReflectionCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        return "forName".equals(name)
                || "getDeclaredMethod".equals(name)
                || "getMethod".equals(name)
                || "newInstance".equals(name)
                || "invoke".equals(name)
                || "getDeclaredField".equals(name)
                || "getField".equals(name);
    }

    private TypeRef firstParameterType(MethodDeclaration declaration, ExtractionSink sink) {
        return declaration.getParameters().isEmpty() ? null : toTypeRef(declaration.getParameter(0).getType(), sink);
    }

    private TypeRef firstArgumentType(MethodCallExpr call, ExtractionSink sink) {
        if (call.getArguments().isEmpty()) {
            return null;
        }

        sink.recordTotalTypeRef();
        try {
            TypeRef result = toTypeRef(call.getArgument(0).calculateResolvedType(), call.getArgument(0).toString(), sink);
            if (Boolean.TRUE.equals(result.unresolved())) {
                sink.recordUnresolvedTypeRef();
            }
            return result;
        } catch (Exception e) {
            sink.recordUnresolvedTypeRef();
            return TypeRefFactory.unresolved(call.getArgument(0).toString(), call.getArgument(0).toString());
        }
    }
}

// 역할: 그래프 저장소의 심볼 메타데이터와 코드 위치 정보를 보관한다.
package com.example.ossdoc.domain.graphstore.entity;

import com.example.ossdoc.domain.graphstore.converter.AccessLevelConverter;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.example.ossdoc.domain.graphstore.enums.*;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.module.entity.FileIndex;
import com.example.ossdoc.domain.module.entity.ModuleEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(
        name = "symbol",
        uniqueConstraints = @UniqueConstraint(name = "ux_symbol_run_qualified", columnNames = {"run_id", "qualified_name"})
)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class SymbolEntity extends BaseAuditedEntity implements Persistable<String> {

    @Id
    @Column(name = "symbol_id", nullable = false)
    private String symbolId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id")
    private ModuleEntity module;

    @Enumerated(EnumType.STRING)
    @Column(name = "symbol_kind", nullable = false)
    private SymbolKind symbolKind;

    @Column(name = "qualified_name", nullable = false, columnDefinition = "text")
    private String qualifiedName;

    @Column(name = "simple_name", columnDefinition = "text")
    private String simpleName;

    @Convert(converter = AccessLevelConverter.class)
    @Column(name = "access")
    private AccessLevel access;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "modifiers", nullable = false, columnDefinition = "jsonb")
    private JsonNode modifiers = JsonNodeFactory.instance.arrayNode();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_symbol_id")
    private SymbolEntity owner;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signature", nullable = false, columnDefinition = "jsonb")
    private JsonNode signature = JsonNodeFactory.instance.objectNode();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_file_id")
    private FileIndex sourceFile;

    @Column(name = "source_start_line")
    private Integer sourceStartLine;

    @Column(name = "source_end_line")
    private Integer sourceEndLine;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false)
    private OriginKind origin = OriginKind.AST;

    @Column(name = "type_kind")
    private String typeKind;

    @Column(name = "source_root")
    private String sourceRoot;

    @Column(name = "doc_comment", columnDefinition = "text")
    private String docComment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "annotations", columnDefinition = "jsonb")
    private JsonNode annotations = JsonNodeFactory.instance.arrayNode();

    /*
     * symbolId는 애플리케이션이 부여하는 값이라 Spring Data의 기본 판별(id == null)로는
     * 신규 여부를 알 수 없어 save()가 항상 merge()로 동작한다.
     * merge()는 관리 사본을 반환하므로 저장에 넘긴 원본 인스턴스는 detached 상태로 남고,
     * GraphStoreIngestService가 저장 이후에 수행하는 owner/source span 연결이 유실된다.
     *
     * 신규 여부를 이 플래그로 직접 알려 persist() 경로를 타게 한다.
     * 조회(@PostLoad)와 저장(@PostPersist) 직후에는 더 이상 신규가 아니므로 false로 뒤집는다.
     */
    @Transient
    @Getter(lombok.AccessLevel.NONE)
    private boolean newEntity = true;

    public SymbolEntity(
            String symbolId,
            RepoRun run,
            ModuleEntity module,
            SymbolKind symbolKind,
            String qualifiedName,
            String simpleName,
            AccessLevel access,
            JsonNode modifiers,
            SymbolEntity owner,
            JsonNode signature,
            FileIndex sourceFile,
            Integer sourceStartLine,
            Integer sourceEndLine,
            OriginKind origin,
            String typeKind,
            String sourceRoot,
            String docComment,
            JsonNode annotations
    ) {
        this.symbolId = symbolId;
        this.run = run;
        this.module = module;
        this.symbolKind = symbolKind;
        this.qualifiedName = qualifiedName;
        this.simpleName = simpleName;
        this.access = access;
        this.modifiers = modifiers;
        this.owner = owner;
        this.signature = signature;
        this.sourceFile = sourceFile;
        this.sourceStartLine = sourceStartLine;
        this.sourceEndLine = sourceEndLine;
        this.origin = origin;
        this.typeKind = typeKind;
        this.sourceRoot = sourceRoot;
        this.docComment = docComment;
        this.annotations = annotations;
    }

    @Override
    public String getId() {
        return symbolId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markNotNew() {
        this.newEntity = false;
    }

    public void assignOwner(SymbolEntity owner) {
        this.owner = owner;
    }

    public void assignModule(ModuleEntity module) {
        this.module = module;
    }

    /**
     * 역할: 심볼이 선언된 소스 파일(FileIndex)을 연결한다.
     */
    public void assignSourceFile(FileIndex sourceFile) {
        this.sourceFile = sourceFile;
    }

    public void assignSourceSpan(Integer startLine, Integer endLine) {
        this.sourceStartLine = startLine;
        this.sourceEndLine = endLine;
    }
}

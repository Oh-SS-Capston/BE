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

@Entity
@Table(
        name = "symbol",
        uniqueConstraints = @UniqueConstraint(name = "ux_symbol_run_qualified", columnNames = {"run_id", "qualified_name"})
)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@AllArgsConstructor
public class SymbolEntity extends BaseAuditedEntity {

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

    public void assignOwner(SymbolEntity owner) {
        this.owner = owner;
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

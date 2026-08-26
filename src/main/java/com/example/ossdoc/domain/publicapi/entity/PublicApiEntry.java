package com.example.ossdoc.domain.publicapi.entity;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "public_api_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PublicApiEntry extends BaseAuditedEntity {

    @EmbeddedId
    private PublicApiEntryId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("runId")
    @JoinColumn(name = "run_id", nullable = false)
    private RepoRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("symbolId")
    @JoinColumn(name = "symbol_id", nullable = false)
    private SymbolEntity symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "symbol_kind", nullable = false)
    private SymbolKind symbolKind;

    @Column(name = "exposure", nullable = false)
    private String exposure;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reason", nullable = false, columnDefinition = "jsonb")
    private JsonNode reason;
}

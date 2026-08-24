package com.example.ossdoc.domain.graphstore.model.projection;

/**
 * symbol_evidence 중복 방지용 경량 key row.
 *
 * <p>기존 link 존재 여부 확인에는 symbol/evidence 엔티티 전체가 필요하지 않고
 * 복합키 값만 필요하다. key만 조회해 대형 프로젝트에서 link 엔티티 전체 로딩으로 인한 heap 사용량을 줄인다.</p>
 */
public record SymbolEvidenceLinkKeyRow(
        String symbolId,
        Long evidenceId
) {
}

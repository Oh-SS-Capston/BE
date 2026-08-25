package com.example.ossdoc.domain.graphstore.model.projection;

/**
 * edge_evidence 중복 방지용 경량 key row.
 *
 * <p>기존 edge-evidence 연결 여부 확인에는 EdgeEvidence 엔티티 전체와 edge/evidence 연관 객체가 필요하지 않다.
 * 복합키 값만 조회해 대형 프로젝트에서 기존 link 로딩 시 발생하는 heap 사용량을 줄인다.</p>
 */
public record EdgeEvidenceLinkKeyRow(
        Long edgeId,
        Long evidenceId
) {
}

package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.graphstore.model.projection.EvidenceLookupRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EvidenceRepository extends JpaRepository<Evidence, Long> {

    /**
     * 성능 최적화를 위해 run 범위 evidence를 한 번에 로드한다.
     */
    List<Evidence> findAllByRun_RunId(String runId);

    /**
     * GraphStore ingest 중복 판별에 필요한 필드만 조회한다.
     *
     * <p>기존 findAllByRun_RunId는 Evidence 엔티티 전체와 큰 text/jsonb 필드를 영속성 컨텍스트에 올린다.
     * projection 조회로 JPA 관리 객체 수와 불필요한 column 로딩을 줄여 대형 프로젝트의 heap peak를 낮춘다.</p>
     */
    @Query("""
            select new com.example.ossdoc.domain.graphstore.model.projection.EvidenceLookupRow(
                e.evidenceId,
                e.evidenceType,
                f.fileId,
                f.path,
                f.fileType,
                e.startLine,
                e.startCol,
                e.endLine,
                e.endCol,
                e.symbol,
                e.snippet,
                e.hash,
                e.rawId,
                e.attrs
            )
            from Evidence e
            left join e.file f
            where e.run.runId = :runId
            """)
    List<EvidenceLookupRow> findLookupRowsByRunId(@Param("runId") String runId);

    Optional<Evidence> findFirstByRun_RunIdAndHash(
            String runId,
            String hash
    );

    List<Evidence> findByRun_RunIdAndEvidenceTypeAndStartLineAndEndLineAndSnippet(
            String runId,
            EvidenceType evidenceType,
            Integer startLine,
            Integer endLine,
            String snippet
    );

    /**
     * 파일까지 일치하는 evidence 중복 여부를 확인한다.
     */
    List<Evidence> findByRun_RunIdAndEvidenceTypeAndFile_FileIdAndStartLineAndEndLineAndSnippet(
            String runId,
            EvidenceType evidenceType,
            Long fileId,
            Integer startLine,
            Integer endLine,
            String snippet
    );

    /**
     * run 범위에서 가장 최근에 적재된 evidence 1건을 조회한다.
     */
    Optional<Evidence> findTopByRun_RunIdOrderByCreatedAtDesc(String runId);
}

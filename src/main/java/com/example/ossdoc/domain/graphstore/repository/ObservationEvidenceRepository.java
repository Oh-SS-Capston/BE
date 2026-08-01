package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.ObservationEvidence;
import com.example.ossdoc.domain.graphstore.entity.ObservationEvidenceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ObservationEvidenceRepository
        extends JpaRepository<
        ObservationEvidence,
        ObservationEvidenceId
        > {

    List<ObservationEvidence>
    findAllByObservation_Run_RunId(String runId);

    /**
     * Observation 삭제 전에 FK 연결을 먼저 제거한다.
     */
    @Modifying
    @Query("""
            DELETE FROM ObservationEvidence oe
            WHERE oe.observation.run.runId = :runId
            """)
    int deleteAllByRunId(
            @Param("runId") String runId
    );
}

package com.example.ossdoc.domain.graphstore.repository;

import com.example.ossdoc.domain.graphstore.entity.Observation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ObservationRepository extends JpaRepository<Observation, Long> {

    List<Observation> findAllByRun_RunId(String runId);

    @Modifying
    @Query("DELETE FROM Observation o WHERE o.run.runId = :runId")
    void deleteAllByRunId(@Param("runId") String runId);
}

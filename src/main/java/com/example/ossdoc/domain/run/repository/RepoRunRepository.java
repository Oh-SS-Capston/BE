package com.example.ossdoc.domain.run.repository;

import com.example.ossdoc.domain.run.entity.RepoRun;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RepoRunRepository extends JpaRepository<RepoRun, String> {

    /**
     * 요청 사용자가 소유한 run인지 확인합니다.
     */
    @Query("""
            SELECT r
            FROM RepoRun r
            WHERE r.runId = :runId
              AND r.owner.id = :ownerId
            """)
    Optional<RepoRun> findOwnedRun(
            @Param("runId") String runId,
            @Param("ownerId") Long ownerId
    );

    /**
     * 사용자 최근 분석 기록 10개를 최신순으로 조회합니다.
     */
    @Query("""
            SELECT r
            FROM RepoRun r
            WHERE r.owner.id = :ownerId
            ORDER BY r.createdAt DESC
            """)
    List<RepoRun> findRecentRunsByOwner(
            @Param("ownerId") Long ownerId,
            Pageable pageable
    );

    default List<RepoRun> findRecentRunsByOwner(Long ownerId) {
        return findRecentRunsByOwner(ownerId, PageRequest.of(0, 10));
    }
}

package com.example.ossdoc.domain.run.repository;

import com.example.ossdoc.domain.run.entity.RepoRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepoRunRepository extends JpaRepository<RepoRun, String> {
    /*
     * 진행률 조회 시 현재 로그인 사용자가 소유한 run인지 검증합니다.
     */
    Optional<RepoRun> findByRunIdAndOwner_Id(String runId, Long ownerId);
}
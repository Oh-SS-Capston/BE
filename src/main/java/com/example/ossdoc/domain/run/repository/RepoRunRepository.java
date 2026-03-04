// domain/run/repository/RepoRunRepository.java
package com.example.ossdoc.domain.run.repository;

import com.example.ossdoc.domain.run.entity.RepoRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepoRunRepository extends JpaRepository<RepoRun, String> {
}
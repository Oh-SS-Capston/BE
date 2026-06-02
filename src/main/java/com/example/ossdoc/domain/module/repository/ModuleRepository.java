package com.example.ossdoc.domain.module.repository;

import com.example.ossdoc.domain.module.entity.ModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModuleRepository extends JpaRepository<ModuleEntity, String> {

    List<ModuleEntity> findAllByRun_RunId(String runId);
}

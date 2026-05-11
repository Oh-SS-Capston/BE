package com.example.ossdoc.domain.rule.repository.custom;

import com.example.ossdoc.domain.rule.dto.projection.AssertionCallProjection;
import com.example.ossdoc.domain.rule.dto.projection.GuardReturnProjection;
import com.example.ossdoc.domain.rule.dto.projection.GuardThrowProjection;
import com.example.ossdoc.domain.rule.dto.projection.PersistenceActionProjection;
import com.example.ossdoc.domain.rule.entity.RuleMiningSignal;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;

import java.util.List;

public interface RuleMiningQueryRepository {

    List<GuardThrowProjection> findGuardThrowSignals(String runId);

    List<GuardReturnProjection> findGuardReturnSignals(String runId);

    List<PersistenceActionProjection> findPersistenceActionSignals(String runId);

    List<AssertionCallProjection> findAssertionCallSignals(String runId);

    List<RuleMiningSignal> findSignalsByTypes(
            String runId,
            List<RuleMiningSignalType> signalTypes
    );
}
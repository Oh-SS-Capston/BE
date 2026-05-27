package com.example.ossdoc.domain.rule.service.miner;

import com.example.ossdoc.domain.rule.dto.projection.PersistenceActionProjection;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.rule.enums.RuleCandidateSource;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
import com.example.ossdoc.domain.rule.repository.RuleCandidateEvidenceRepository;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.rule.repository.RuleMiningSignalRepository;
import com.example.ossdoc.domain.rule.repository.custom.RuleMiningQueryRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class PersistenceActionMiner extends AbstractRuleCandidateMiner {

    private final RuleMiningQueryRepository ruleMiningQueryRepository;
    private final RuleMiningSignalRepository ruleMiningSignalRepository;

    public PersistenceActionMiner(
            RuleMiningQueryRepository ruleMiningQueryRepository,
            RuleMiningSignalRepository ruleMiningSignalRepository,
            RuleCandidateRepository ruleCandidateRepository,
            RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository
    ) {
        super(ruleCandidateRepository, ruleCandidateEvidenceRepository);
        this.ruleMiningQueryRepository = ruleMiningQueryRepository;
        this.ruleMiningSignalRepository = ruleMiningSignalRepository;
    }

    @Override
    public RuleCandidateKind supports() {
        return RuleCandidateKind.PERSISTENCE_ACTION;
    }

    @Override
    @Transactional
    public int mine(RepoRun run) {
        boolean hasPersistenceFramework = ruleMiningSignalRepository.existsByRun_RunIdAndSignalType(
                run.getRunId(),
                RuleMiningSignalType.TRANSACTION_BOUNDARY
        );

        if (!hasPersistenceFramework) {
            log.info(
                    "[RULE-MINING] PersistenceActionMiner skipped: no @Transactional signals found. runId={}",
                    run.getRunId()
            );
            return 0;
        }

        List<PersistenceActionProjection> projections =
                ruleMiningQueryRepository.findPersistenceActionSignals(run.getRunId());

        List<CandidateDraft> drafts = new ArrayList<>();

        for (PersistenceActionProjection projection : projections) {
            RuleMiningSignalType actionType = projection.actionType();
            String methodName = safeSymbolName(projection.methodSymbol());
            String actionText = projection.actionText();

            String actionLabel = toActionLabel(actionType);

            String ruleKey = "PERSISTENCE_ACTION:"
                    + normalizeKeyPart(safeSymbolId(projection.methodSymbol()))
                    + ":"
                    + normalizeKeyPart(actionType.name())
                    + ":"
                    + normalizeKeyPart(actionText);

            String title = "영속성 " + actionLabel + " 호출 규칙";
            String description = "메서드 " + methodName
                    + "에서 " + actionLabel + " 성격의 repository/entity manager 호출이 발견되었습니다.";

            RuleCandidateConfidence confidence = RuleCandidateConfidence.MEDIUM;

            drafts.add(candidateDraft(
                    ruleKey,
                    RuleCandidateKind.PERSISTENCE_ACTION,
                    confidence,
                    RuleCandidateSource.GRAPHSTORE,
                    projection.methodSymbol(),
                    safeSymbolId(projection.methodSymbol()),
                    title,
                    description,
                    ruleKey,
                    score(confidence, 1),
                    1,
                    false,
                    summaryNode("PERSISTENCE_" + actionLabel.toUpperCase(), null, actionText),
                    impactNode("MEDIUM", "데이터 저장, 수정, 삭제 흐름의 핵심 동작일 가능성이 있습니다."),
                    metaNode("PersistenceActionMiner")
                            .put("actionType", actionType.name()),
                    List.of(
                            evidenceDraft(
                                    projection.actionSignal(),
                                    actionType.name(),
                                    WEIGHT_PRIMARY,
                                    "영속성 동작 호출 신호"
                            )
                    )
            ));
        }

        int saved = saveCandidateDrafts(run, drafts);

        log.info("[RULE-MINING] PersistenceActionMiner completed. runId={}, candidates={}", run.getRunId(), saved);
        return saved;
    }

    private String toActionLabel(RuleMiningSignalType actionType) {
        if (actionType == RuleMiningSignalType.REPOSITORY_SAVE) {
            return "save";
        }
        if (actionType == RuleMiningSignalType.REPOSITORY_UPDATE) {
            return "update";
        }
        if (actionType == RuleMiningSignalType.REPOSITORY_DELETE) {
            return "delete";
        }
        return "persistence";
    }
}
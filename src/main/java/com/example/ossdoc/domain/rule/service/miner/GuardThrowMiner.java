package com.example.ossdoc.domain.rule.service.miner;

import com.example.ossdoc.domain.rule.dto.projection.GuardThrowProjection;
import com.example.ossdoc.domain.rule.entity.RuleCandidate;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.rule.enums.RuleCandidateSource;
import com.example.ossdoc.domain.rule.repository.RuleCandidateEvidenceRepository;
import com.example.ossdoc.domain.rule.repository.RuleCandidateRepository;
import com.example.ossdoc.domain.rule.repository.custom.RuleMiningQueryRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
public class GuardThrowMiner extends AbstractRuleCandidateMiner {

    private final RuleMiningQueryRepository ruleMiningQueryRepository;

    public GuardThrowMiner(
            RuleMiningQueryRepository ruleMiningQueryRepository,
            RuleCandidateRepository ruleCandidateRepository,
            RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository
    ) {
        super(ruleCandidateRepository, ruleCandidateEvidenceRepository);
        this.ruleMiningQueryRepository = ruleMiningQueryRepository;
    }

    @Override
    public RuleCandidateKind supports() {
        return RuleCandidateKind.GUARD_THROW;
    }

    @Override
    @Transactional
    public int mine(RepoRun run) {
        List<GuardThrowProjection> projections =
                ruleMiningQueryRepository.findGuardThrowSignals(run.getRunId());

        int saved = 0;

        for (GuardThrowProjection projection : projections) {
            RuleCandidateConfidence confidence = confidenceByDistance(projection.lineDistance());

            String methodName = safeSymbolName(projection.methodSymbol());
            String conditionText = projection.conditionSignal().getNormalizedText();
            String throwText = projection.throwSignal().getNormalizedText();

            String ruleKey = "GUARD_THROW:"
                    + normalizeKeyPart(safeSymbolId(projection.methodSymbol()))
                    + ":"
                    + normalizeKeyPart(throwText);

            String title = "조건 검증 후 예외를 던지는 방어 규칙";
            String description = "메서드 " + methodName
                    + "에서 조건문 이후 예외 throw가 근접하게 나타나는 guard clause 후보입니다.";

            String fingerprint = ruleKey;

            RuleCandidate candidate = upsertCandidate(
                    run,
                    ruleKey,
                    RuleCandidateKind.GUARD_THROW,
                    confidence,
                    RuleCandidateSource.MIXED,
                    projection.methodSymbol(),
                    safeSymbolId(projection.methodSymbol()),
                    title,
                    description,
                    fingerprint,
                    score(confidence, 2),
                    2,
                    false,
                    summaryNode("IF_CONDITION_THEN_THROW", conditionText, throwText),
                    impactNode("MEDIUM", "잘못된 입력이나 상태를 초기에 차단하는 방어 로직일 가능성이 있습니다."),
                    metaNode("GuardThrowMiner")
                            .put("lineDistance", projection.lineDistance())
            );

            saveEvidenceLinks(candidate, List.of(
                    evidenceDraft(
                            projection.conditionSignal(),
                            "IF_CONDITION",
                            WEIGHT_PRIMARY,
                            "조건 검증 신호"
                    ),
                    evidenceDraft(
                            projection.throwSignal(),
                            "THROW_STATEMENT",
                            WEIGHT_PRIMARY,
                            "조건 실패 시 예외 발생 신호"
                    )
            ));

            saved++;
        }

        log.info("[RULE-MINING] GuardThrowMiner completed. runId={}, candidates={}", run.getRunId(), saved);
        return saved;
    }
}
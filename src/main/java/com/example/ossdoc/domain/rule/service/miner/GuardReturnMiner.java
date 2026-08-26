package com.example.ossdoc.domain.rule.service.miner;

import com.example.ossdoc.domain.rule.dto.projection.GuardReturnProjection;
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

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class GuardReturnMiner extends AbstractRuleCandidateMiner {

    private final RuleMiningQueryRepository ruleMiningQueryRepository;

    public GuardReturnMiner(
            RuleMiningQueryRepository ruleMiningQueryRepository,
            RuleCandidateRepository ruleCandidateRepository,
            RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository
    ) {
        super(ruleCandidateRepository, ruleCandidateEvidenceRepository);
        this.ruleMiningQueryRepository = ruleMiningQueryRepository;
    }

    @Override
    public RuleCandidateKind supports() {
        return RuleCandidateKind.GUARD_RETURN;
    }

    @Override
    @Transactional
    public int mine(RepoRun run) {
        List<GuardReturnProjection> projections =
                ruleMiningQueryRepository.findGuardReturnSignals(run.getRunId());

        List<CandidateDraft> drafts = new ArrayList<>();

        for (GuardReturnProjection projection : projections) {
            RuleCandidateConfidence confidence = confidenceByDistance(projection.lineDistance());

            String methodName = safeSymbolName(projection.methodSymbol());
            String conditionText = projection.conditionSignal().getNormalizedText();
            String returnText = projection.returnSignal().getNormalizedText();
            String errorText = projection.errorResponseSignal() == null
                    ? null
                    : projection.errorResponseSignal().getNormalizedText();

            String actionText = firstNonBlank(errorText, returnText);

            String ruleKey = "GUARD_RETURN:"
                    + normalizeKeyPart(safeSymbolId(projection.methodSymbol()))
                    + ":"
                    + normalizeKeyPart(actionText);

            String title = "조건 검증 후 조기 반환하는 방어 규칙";
            String description = "메서드 " + methodName
                    + "에서 조건문 이후 return 또는 error response가 근접하게 나타나는 guard return 후보입니다.";

            int supportCount = projection.errorResponseSignal() == null ? 2 : 3;

            List<CandidateEvidenceDraft> evidences = new ArrayList<>();
            evidences.add(evidenceDraft(
                    projection.conditionSignal(),
                    "IF_CONDITION",
                    WEIGHT_PRIMARY,
                    "조건 검증 신호"
            ));
            evidences.add(evidenceDraft(
                    projection.returnSignal(),
                    "RETURN_STATEMENT",
                    WEIGHT_PRIMARY,
                    "조건 이후 조기 return 신호"
            ));

            if (projection.errorResponseSignal() != null) {
                evidences.add(evidenceDraft(
                        projection.errorResponseSignal(),
                        "ERROR_RESPONSE",
                        WEIGHT_SUPPORTING,
                        "에러 응답 반환 신호"
                ));
            }

            drafts.add(candidateDraft(
                    ruleKey,
                    RuleCandidateKind.GUARD_RETURN,
                    confidence,
                    RuleCandidateSource.MIXED,
                    projection.methodSymbol(),
                    safeSymbolId(projection.methodSymbol()),
                    title,
                    description,
                    ruleKey,
                    score(confidence, supportCount),
                    supportCount,
                    false,
                    summaryNode("IF_CONDITION_THEN_RETURN", conditionText, actionText),
                    impactNode("MEDIUM", "잘못된 입력이나 상태에서 흐름을 조기에 종료하는 방어 로직일 가능성이 있습니다."),
                    metaNode("GuardReturnMiner")
                            .put("lineDistance", projection.lineDistance())
                            .put("hasErrorResponse", projection.errorResponseSignal() != null),
                    evidences
            ));
        }

        int saved = saveCandidateDrafts(run, drafts);

        log.info("[RULE-MINING] GuardReturnMiner completed. runId={}, candidates={}", run.getRunId(), saved);
        return saved;
    }
}
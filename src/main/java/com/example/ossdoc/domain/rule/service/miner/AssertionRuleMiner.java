package com.example.ossdoc.domain.rule.service.miner;

import com.example.ossdoc.domain.rule.dto.projection.AssertionCallProjection;
import com.example.ossdoc.domain.rule.enums.RuleCandidateConfidence;
import com.example.ossdoc.domain.rule.enums.RuleCandidateKind;
import com.example.ossdoc.domain.rule.enums.RuleCandidateSource;
import com.example.ossdoc.domain.rule.enums.RuleMiningSignalType;
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
public class AssertionRuleMiner extends AbstractRuleCandidateMiner {

    private final RuleMiningQueryRepository ruleMiningQueryRepository;

    public AssertionRuleMiner(
            RuleMiningQueryRepository ruleMiningQueryRepository,
            RuleCandidateRepository ruleCandidateRepository,
            RuleCandidateEvidenceRepository ruleCandidateEvidenceRepository
    ) {
        super(ruleCandidateRepository, ruleCandidateEvidenceRepository);
        this.ruleMiningQueryRepository = ruleMiningQueryRepository;
    }

    @Override
    public RuleCandidateKind supports() {
        return RuleCandidateKind.ASSERTION_RULE;
    }

    @Override
    @Transactional
    public int mine(RepoRun run) {
        List<AssertionCallProjection> projections =
                ruleMiningQueryRepository.findAssertionCallSignals(run.getRunId());

        List<CandidateDraft> drafts = new ArrayList<>();

        for (AssertionCallProjection projection : projections) {
            RuleMiningSignalType assertionType = projection.assertionType();
            String methodName = safeSymbolName(projection.methodSymbol());
            String assertionText = projection.assertionText();

            String ruleKey = "ASSERTION_RULE:"
                    + normalizeKeyPart(safeSymbolId(projection.methodSymbol()))
                    + ":"
                    + normalizeKeyPart(assertionType.name())
                    + ":"
                    + normalizeKeyPart(assertionText);

            String title = "전제조건 검증 호출 규칙";
            String description = "메서드 " + methodName
                    + "에서 require/assert/check 계열의 전제조건 검증 호출이 발견되었습니다.";

            RuleCandidateConfidence confidence = assertionType == RuleMiningSignalType.REQUIRE_CALL
                    ? RuleCandidateConfidence.HIGH
                    : RuleCandidateConfidence.MEDIUM;

            drafts.add(candidateDraft(
                    ruleKey,
                    RuleCandidateKind.ASSERTION_RULE,
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
                    summaryNode(assertionType.name(), null, assertionText),
                    impactNode("MEDIUM", "메서드 실행 전 입력값 또는 상태를 검증하는 규칙일 가능성이 있습니다."),
                    metaNode("AssertionRuleMiner")
                            .put("assertionType", assertionType.name()),
                    List.of(
                            evidenceDraft(
                                    projection.assertionSignal(),
                                    assertionType.name(),
                                    WEIGHT_PRIMARY,
                                    "전제조건 검증 호출 신호"
                            )
                    )
            ));
        }

        int saved = saveCandidateDrafts(run, drafts);

        log.info("[RULE-MINING] AssertionRuleMiner completed. runId={}, candidates={}", run.getRunId(), saved);
        return saved;
    }
}
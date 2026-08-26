package com.example.ossdoc.domain.rule.entity;

import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class RuleCandidateSummaryId implements Serializable {

    @Column(name = "run_id")
    private String runId;

    @Column(name = "rule_key")
    private String ruleKey;
}

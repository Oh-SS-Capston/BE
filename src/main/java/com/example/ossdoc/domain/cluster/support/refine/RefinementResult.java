package com.example.ossdoc.domain.cluster.support.refine;

import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;

import java.util.List;
import java.util.Map;

/**
 * 보정 규칙 1건의 실행 결과.
 *
 * @param subsystems 보정이 반영된 subsystem 목록
 * @param meta       baseline 측정용 규칙별 메타. 산출물 algorithm.refiner.rules 에 기록된다.
 */
public record RefinementResult(List<Subsystem> subsystems, Map<String, Object> meta) {
}

package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 최종 Subsystem ID를 deterministic하게 생성하는 서비스.
 *
 * 기존 ss_001, ss_002 방식은 Leiden의 cluster 번호가 달라지면
 * 같은 subsystem임에도 ID가 달라질 수 있었다.
 *
 * 개선 버전에서는 commit SHA와 최종 memberSymbolId 집합을 이용해
 * subsystem ID를 생성한다.
 *
 * 따라서 같은 commit에서 같은 member 구성을 가진 subsystem은
 * 항상 동일한 ID를 갖는다.
 */
@Service
public class SubsystemIdentityService {

    /**
     * 최종 subsystem 목록의 ID를 안정적인 ID로 다시 생성한다.
     *
     * 반드시 SubsystemRefiner가 끝난 뒤 호출해야 한다.
     * Refiner가 subsystem membership을 변경할 수 있기 때문이다.
     */
    public List<Subsystem> rekey(
            List<Subsystem> subsystems,
            String commitSha
    ) {
        return subsystems
                .stream()
                .map(subsystem ->
                        subsystem.toBuilder()
                                .subsystemId(
                                        stableId(
                                                commitSha,
                                                subsystem.getMemberSymbolIds()
                                        )
                                )
                                .build()
                )

                /*
                 * subsystem 자체의 반환 순서도 ID 기준으로 정렬한다.
                 *
                 * 같은 분석 결과에서 JSON 배열 순서까지 안정적으로 유지하기 위한 처리다.
                 */
                .sorted(Comparator.comparing(Subsystem::getSubsystemId))
                .toList();
    }

    /**
     * commit SHA와 memberSymbolId를 이용하여 deterministic ID를 생성한다.
     */
    String stableId(
            String commitSha,
            List<String> memberSymbolIds
    ) {
        /*
         * member 목록 순서가 달라도 같은 subsystem으로 판단해야 하므로
         * memberSymbolId를 반드시 정렬한다.
         */
        String members = memberSymbolIds == null
                ? ""
                : memberSymbolIds
                .stream()
                .filter(id -> id != null && !id.isBlank())
                .sorted()
                .reduce((first, second) -> first + "\n" + second)
                .orElse("");

        /*
         * commit SHA를 함께 사용한다.
         *
         * 같은 member 이름이더라도 서로 다른 commit의 분석 결과는
         * 별개의 subsystem identity로 관리할 수 있다.
         */
        String payload =
                (commitSha == null ? "" : commitSha)
                        + "\n"
                        + members;

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(
                            payload.getBytes(StandardCharsets.UTF_8)
                    );

            /*
             * SHA-256 전체 문자열은 ID로 사용하기에 너무 길기 때문에
             * 앞의 6 byte, 즉 12자리 hex 문자열만 사용한다.
             *
             * 예:
             * ss_a84f92bc17cd
             */
            return "ss_"
                    + HexFormat
                    .of()
                    .formatHex(digest, 0, 6);

        } catch (NoSuchAlgorithmException e) {
            /*
             * Java 표준 환경에서는 SHA-256을 지원하므로
             * 정상적인 실행 환경에서 발생할 가능성은 매우 낮다.
             */
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    e
            );
        }
    }
}
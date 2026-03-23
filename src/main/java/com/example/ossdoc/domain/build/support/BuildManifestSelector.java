package com.example.ossdoc.domain.build.support;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.dto.json.BuildModuleManifest;
import com.example.ossdoc.domain.build.enums.BuildMode;
import org.springframework.stereotype.Component;

/**
 * 역할:
 * Gradle/Maven 빌드 결과 중 더 품질이 높은 매니페스트를 선택한다.
 *
 * 책임:
 * 1) BuildMode 우선 비교(FULL > COMPILE_ONLY > SOURCE_ONLY > FAILED)
 * 2) 동점 시 classesDirs/모듈 수/실패 수 순으로 비교
 * 3) 완전 동점 시 기존 정책(Gradle 우선) 유지
 */
@Component
public class BuildManifestSelector {

    /**
     * 역할:
     * 두 매니페스트 중 최종 채택 대상을 반환한다.
     *
     * 책임:
     * 내부 품질 점수 비교 결과를 정책에 맞게 해석해 반환한다.
     */
    public BuildManifest selectBetter(BuildManifest gradleManifest, BuildManifest mavenManifest) {
        // 점수 비교 결과가 0 이상이면 left(Gradle) 선택: 동률 시 기존 우선순위 유지
        int comparison = compareQuality(gradleManifest, mavenManifest);
        return comparison >= 0 ? gradleManifest : mavenManifest;
    }

    private int compareQuality(BuildManifest left, BuildManifest right) {
        // 1순위: buildMode
        int modeComparison = Integer.compare(buildModeScore(left.getBuildMode()), buildModeScore(right.getBuildMode()));
        if (modeComparison != 0) {
            return modeComparison;
        }

        // 2순위: classesDirs 확보량(바이트코드 분석 가능성)
        int leftClasses = classesDirCount(left);
        int rightClasses = classesDirCount(right);
        int classesComparison = Integer.compare(leftClasses, rightClasses);
        if (classesComparison != 0) {
            return classesComparison;
        }

        // 3순위: 모듈 수
        int leftModules = moduleCount(left);
        int rightModules = moduleCount(right);
        int moduleComparison = Integer.compare(leftModules, rightModules);
        if (moduleComparison != 0) {
            return moduleComparison;
        }

        // 4순위: 실패 수(적을수록 우수)
        int leftFailureCount = failureCount(left);
        int rightFailureCount = failureCount(right);
        int failureComparison = Integer.compare(rightFailureCount, leftFailureCount);
        if (failureComparison != 0) {
            return failureComparison;
        }

        // 동률이면 기존 우선순위(Gradle 우선)를 유지한다.
        return 0;
    }

    private int buildModeScore(BuildMode mode) {
        if (mode == null) return 0;
        return switch (mode) {
            case FULL -> 4;
            case COMPILE_ONLY -> 3;
            case SOURCE_ONLY -> 2;
            case FAILED -> 1;
        };
    }

    private int classesDirCount(BuildManifest manifest) {
        if (manifest == null || manifest.getModules() == null) {
            return 0;
        }

        int count = 0;
        for (BuildModuleManifest module : manifest.getModules()) {
            if (module == null || module.getClassesDirs() == null) {
                continue;
            }
            count += module.getClassesDirs().size();
        }
        return count;
    }

    private int moduleCount(BuildManifest manifest) {
        return manifest == null || manifest.getModules() == null ? 0 : manifest.getModules().size();
    }

    private int failureCount(BuildManifest manifest) {
        return manifest == null || manifest.getFailures() == null ? 0 : manifest.getFailures().size();
    }
}

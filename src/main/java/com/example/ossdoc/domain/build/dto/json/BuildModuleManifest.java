package com.example.ossdoc.domain.build.dto.json;

import lombok.*;

import java.util.List;

@Getter @Builder
@NoArgsConstructor @AllArgsConstructor
public class BuildModuleManifest {

    private String moduleId; // gradle projectPath (":core")
    private String name;

    private List<String> sourceRoots;
    private List<String> testRoots;
    private List<String> resourceRoots;

    private List<String> classesDirs;       // 있으면 ASM 가능
    private List<String> compileClasspath;  // 있으면 타입해결 향상
    private List<String> runtimeClasspath;

    private String status; // OK|PARTIAL|SKIPPED|FAILED
    private BuildFailure failReason;
}

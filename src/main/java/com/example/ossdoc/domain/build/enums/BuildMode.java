package com.example.ossdoc.domain.build.enums;

public enum BuildMode {
    FULL,         // classesDirs + classpath까지 확보(ASM까지 가능)
    COMPILE_ONLY, // 컴파일은 됐는데 classpath/덤프 일부 부족
    SOURCE_ONLY,  // 컴파일 실패했지만 모듈/소스루트는 확보(AST는 가능)
    FAILED        // 감지/덤프도 못함
}

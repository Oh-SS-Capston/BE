package com.example.ossdoc.domain.extraction.service.support.evidence;

/** Evidence가 가리키는 코드 범위의 의미적 단위. */
public enum EvidenceGranularity {

    FILE("file"),
    TYPE("type"),
    MEMBER("member"),
    PARAMETER("parameter"),
    ANNOTATION("annotation"),
    EXPRESSION("expression"),
    MODULE_DIRECTIVE("module_directive"),
    INSTRUCTION("instruction"),
    RESOURCE_ENTRY("resource_entry");

    private final String code;

    EvidenceGranularity(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

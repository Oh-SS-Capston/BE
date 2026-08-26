package com.example.ossdoc.domain.extraction.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 자바 수정자.
 *
 * <p>{@code DEFAULT}, {@code SEALED}, {@code NON_SEALED}은 현재 extractor에서 매핑하지 않으나,
 * Java 17+ sealed class 지원 시 필요하므로 유지한다.
 * <ul>
 *   <li>{@code DEFAULT} — 인터페이스 default 메서드 수정자</li>
 *   <li>{@code SEALED} — 상속 허용 타입을 제한하는 sealed class/interface 수정자</li>
 *   <li>{@code NON_SEALED} — sealed 계층에서 하위 클래스의 상속 제한을 해제하는 수정자</li>
 * </ul>
 */
public enum Modifier {
    STATIC("static"),
    FINAL("final"),
    ABSTRACT("abstract"),
    SYNCHRONIZED("synchronized"),
    NATIVE("native"),
    STRICTFP("strictfp"),
    TRANSIENT("transient"),
    VOLATILE("volatile"),
    DEFAULT("default"),
    SEALED("sealed"),
    NON_SEALED("non-sealed");

    private final String code;

    Modifier(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}

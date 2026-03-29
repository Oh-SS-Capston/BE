package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.AccessLevel;
import com.example.ossdoc.domain.extraction.enums.ModifierKind;
import com.example.ossdoc.domain.extraction.enums.SymbolFactKind;
import com.example.ossdoc.domain.extraction.enums.SymbolOriginKind;
import com.example.ossdoc.domain.extraction.enums.TypeKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 심볼(타입/메서드/필드 등) 원시 사실
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SymbolFact(
        @JsonProperty("symbol")
        String symbol,

        @JsonProperty("kind")
        SymbolFactKind kind,

        @JsonProperty("type_kind")
        TypeKind typeKind,

        @JsonProperty("name")
        String name,

        @JsonProperty("qualified_name")
        String qualifiedName,

        /**
         * method / constructor / field의 owner type
         */
        @JsonProperty("owner_type_symbol")
        String ownerTypeSymbol,

        /**
         * type의 package symbol
         */
        @JsonProperty("package_symbol")
        String packageSymbol,

        /**
         * 추출 시점의 모듈 이름
         */
        @JsonProperty("module")
        String module,

        /**
         * AST 관점 source root
         */
        @JsonProperty("source_root")
        String sourceRoot,

        /**
         * ASM 관점 bytecode root
         */
        @JsonProperty("bytecode_root")
        String bytecodeRoot,

        /**
         * 중첩 타입이면 바깥 타입 심볼
         */
        @JsonProperty("nested_in")
        String nestedIn,

        @JsonProperty("access")
        AccessLevel access,

        @JsonProperty("modifiers")
        Set<ModifierKind> modifiers,

        @JsonProperty("origin")
        SymbolOriginKind origin,

        @JsonProperty("annotations")
        List<TypeRef> annotations,

        @JsonProperty("evidence_ids")
        List<String> evidenceIds,

        @JsonProperty("attrs")
        Map<String, Object> attrs,

        /**
         * method/constructor/field signature
         */
        @JsonProperty("signature")
        SignatureFact signature,

        /**
         * TYPE 심볼용 부가 정보
         */
        @JsonProperty("super_type_ref")
        TypeRef superTypeRef,

        @JsonProperty("interface_type_refs")
        List<TypeRef> interfaceTypeRefs,

        @JsonProperty("source_file")
        String sourceFile,

        /**
         * bytecode 관점 부가 정보
         */
        @JsonProperty("is_bridge")
        Boolean isBridge,

        @JsonProperty("is_synthetic")
        Boolean isSynthetic,

        @JsonProperty("bytecode_descriptor")
        String bytecodeDescriptor,

        /**
         * 같은 파일/클래스 내의 local call 후보
         */
        @JsonProperty("local_calls")
        List<String> localCalls
) {
}

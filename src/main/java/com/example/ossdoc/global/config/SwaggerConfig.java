package com.example.ossdoc.global.config;


import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import com.example.ossdoc.global.apiPayload.code.status.SuccessStatus;
import com.example.ossdoc.global.validation.annotation.ApiErrorCodeExample;
import com.example.ossdoc.global.validation.annotation.ApiErrorCodeExamples;
import com.example.ossdoc.global.validation.annotation.ApiSuccessCodeExample;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Configuration
public class SwaggerConfig {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public OpenAPI speakOnAPI() {
        Info info = new Info()
                .title("Oh! SS API")
                .description("Oh! SS API 명세서")
                .version("1.0.0");

        String jwtSchemeName = "JWT TOKEN";
        // API 요청헤더에 인증정보 포함
       //SecurityRequirement securityRequirement = new SecurityRequirement().addList(jwtSchemeName);
        // SecuritySchemes 등록
        Components components = new Components()
                .addSecuritySchemes(jwtSchemeName, new SecurityScheme()
                        .name(jwtSchemeName)
                        .type(SecurityScheme.Type.HTTP) // HTTP 방식
                        .scheme("bearer")
                        .bearerFormat("JWT"));

        return new OpenAPI()
                .addServersItem(new Server().url("/"))
                .info(info)
               // .addSecurityItem(securityRequirement)
                .components(components);
    }

    @Bean
    public OperationCustomizer customize() {
        return (operation, handlerMethod) -> {
            ApiErrorCodeExamples errorAnnotations = handlerMethod.getMethodAnnotation(ApiErrorCodeExamples.class);
            if (errorAnnotations != null) {
                for (ApiErrorCodeExample e : errorAnnotations.value()) {
                    handleErrorCode(operation, e.value(), e.name());
                }
            } else {
                ApiErrorCodeExample single = handlerMethod.getMethodAnnotation(ApiErrorCodeExample.class);
                if (single != null) {
                    handleErrorCode(operation, single.value(), single.name());
                }
            }

            ApiSuccessCodeExample successAnnotation = handlerMethod.getMethodAnnotation(ApiSuccessCodeExample.class);
            if (successAnnotation != null) {
                generateSuccessCodeResponseExample(operation, successAnnotation.resultClass());
            }

            return operation;
        };
    }

    private void handleErrorCode(Operation operation, Class<? extends Enum<?>> enumClass, String name) {
        for (Enum<?> constant : enumClass.getEnumConstants()) {
            if (constant.name().equals(name) && constant instanceof BaseCode errorCode) {
                generateSingleErrorCodeResponseExample(operation, errorCode);
                break;
            }
        }
    }

    private void generateSingleErrorCodeResponseExample(Operation operation, BaseCode errorCode) {
        ReasonDTO reason = errorCode.getReasonHttpStatus();
        String httpStatusCode = String.valueOf(reason.getHttpStatus().value());

        String exampleJson = String.format("""
        {
          "isSuccess": false,
          "code": "%s",
          "message": "%s"
        }
        """, reason.getCode(), reason.getMessage());

        io.swagger.v3.oas.models.responses.ApiResponse apiResponse =
                operation.getResponses().computeIfAbsent(httpStatusCode, code ->
                        new io.swagger.v3.oas.models.responses.ApiResponse()
                                .description("에러 응답")
                                .content(new Content()));

        MediaType mediaType = apiResponse.getContent()
                .computeIfAbsent("application/json", k -> new MediaType());

        mediaType.addExamples(errorCode.getReason().getCode(), new Example().value(exampleJson));
    }

    private void generateSuccessCodeResponseExample(Operation operation, Class<?> resultClass) {
        ReasonDTO reason = SuccessStatus._OK.getReasonHttpStatus();
        String httpStatusCode = String.valueOf(reason.getHttpStatus().value());

        String resultJson;
        try {
            Object dtoInstance = generateDtoFromSchemaExample(resultClass); // ← 수정된 부분
            String dtoJson = objectMapper.writeValueAsString(dtoInstance);

            resultJson = String.format("""
            {
              "isSuccess": true,
              "code": "%s",
              "message": "%s",
              "result": %s
            }
            """, reason.getCode(), reason.getMessage(), dtoJson);

        } catch (Exception e) {
            resultJson = String.format("""
            {
              "isSuccess": true,
              "code": "%s",
              "message": "%s",
              "result": {}
            }
            """, reason.getCode(), reason.getMessage());
        }

        Content content = new Content();
        MediaType mediaType = new MediaType();
        content.addMediaType("application/json", mediaType);

        io.swagger.v3.oas.models.responses.ApiResponse apiResponse =
                new io.swagger.v3.oas.models.responses.ApiResponse()
                        .description("성공 응답")
                        .content(content);

        operation.getResponses().put(httpStatusCode, apiResponse);
        mediaType.addExamples("COMMON200", new Example().value(resultJson));
    }

    private Object generateDtoFromSchemaExample(Class<?> dtoClass) throws Exception {
        if (dtoClass == null) return null;
        if (dtoClass == Void.class || dtoClass == void.class) return null;
        if (isSimpleValueType(dtoClass)) return createSimpleValue(dtoClass);

        Object instance;
        try {
            // 1. 기본 생성자 시도 (권장)
            instance = dtoClass.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            // 2. 기본 생성자가 없으면 Unsafe 사용 (Fallback)
            log.debug("기본 생성자 없음. Unsafe를 사용하여 인스턴스 생성 - class: {}", dtoClass.getSimpleName());
            try {
                @SuppressWarnings("unchecked")
                Object unsafeObj = sun.misc.Unsafe.class.getDeclaredField("theUnsafe").get(null);
                sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeObj;
                instance = unsafe.allocateInstance(dtoClass);
            } catch (Exception unsafe_e) {
                log.warn("기본 생성자 및 Unsafe 사용 불가 - class: {}. @NoArgsConstructor 추가 권장",
                        dtoClass.getSimpleName(), unsafe_e);
                return null;
            }
        }

        for (Field field : dtoClass.getDeclaredFields()) {
            field.setAccessible(true);

            Schema schema = field.getAnnotation(Schema.class);
            if (schema == null) continue;

            String exampleValue = schema.example();
            if (exampleValue.isEmpty()) continue;

            Class<?> fieldType = field.getType();

            try {
                if (fieldType == String.class) {
                    field.set(instance, exampleValue);

                } else if (fieldType == Integer.class || fieldType == int.class) {
                    field.set(instance, Integer.parseInt(exampleValue));

                } else if (fieldType == Long.class || fieldType == long.class) {
                    field.set(instance, Long.parseLong(exampleValue));

                } else if (fieldType == LocalDateTime.class) {
                    try {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
                        LocalDateTime parsed = LocalDateTime.parse(exampleValue.trim(), formatter);
                        field.set(instance, parsed);
                    } catch (DateTimeParseException e) {
                        log.debug("LocalDateTime 파싱 실패: {}. null로 처리", exampleValue);
                        field.set(instance, null);
                    }
                } else if (fieldType.isEnum()) {
                    // Enum 타입 처리
                    try {
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        Enum<?> enumValue = Enum.valueOf((Class) fieldType, exampleValue);
                        field.set(instance, enumValue);
                    } catch (IllegalArgumentException enumEx) {
                        log.debug("Enum 값 {} 찾을 수 없음 - {}. null로 처리", exampleValue, fieldType.getSimpleName());
                        field.set(instance, null);
                    }
                } else if (List.class.isAssignableFrom(fieldType)) {
                    // 리스트 타입 처리
                    Type genericType = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
                    if (genericType instanceof Class<?> genericClass) {
                        Object childDto = generateDtoFromSchemaExample(genericClass);
                        field.set(instance, childDto != null ? List.of(childDto) : List.of());
                    }

                } else {
                    // nested DTO 객체 처리
                    Object nestedObject = generateDtoFromSchemaExample(fieldType);
                    field.set(instance, nestedObject);
                }
            } catch (Exception fieldEx) {
                log.debug("필드 {} 설정 실패 - class: {}", field.getName(), dtoClass.getSimpleName());
                // 필드 설정 실패 시 다음 필드로 계속
            }
        }

        return instance;
    }

    private boolean isSimpleValueType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Integer.class || type == Long.class
                || type == Boolean.class
                || type == Double.class || type == Float.class
                || type == Short.class || type == Byte.class
                || type == Character.class
                || type == LocalDateTime.class
                || type.isEnum();
    }

    private Object createSimpleValue(Class<?> type) {
        if (type == String.class) return "";
        if (type == Integer.class || type == int.class) return 0;
        if (type == Long.class || type == long.class) return 0L;
        if (type == Boolean.class || type == boolean.class) return false;
        if (type == Double.class || type == double.class) return 0.0d;
        if (type == Float.class || type == float.class) return 0.0f;
        if (type == Short.class || type == short.class) return (short) 0;
        if (type == Byte.class || type == byte.class) return (byte) 0;
        if (type == Character.class || type == char.class) return '\0';
        if (type == LocalDateTime.class) return LocalDateTime.now();

        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return (constants != null && constants.length > 0) ? constants[0] : null;
        }

        return null;
    }

}

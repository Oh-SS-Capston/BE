package com.example.ossdoc.global.apiPayload.exception;


import com.example.ossdoc.global.apiPayload.ApiResponse;
import com.example.ossdoc.global.apiPayload.code.BaseCode;
import com.example.ossdoc.global.apiPayload.code.ReasonDTO;
import com.example.ossdoc.global.apiPayload.code.status.ErrorStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 전역 예외 처리 클래스
 *
 * 애플리케이션에서 발생하는 모든 예외를 중앙에서 처리하고,
 * 공통 응답 포맷(ApiResponse)으로 클라이언트에게 전달합니다.
 *
 * 처리하는 예외 종류:
 * - ConstraintViolationException: @Valid 검증 실패
 * - MethodArgumentNotValidException: @RequestBody 검증 실패
 * - GeneralException: 비즈니스 로직에서 명시적으로 throw한 예외
 * - 기타 예외: 예상치 못한 모든 예외 (500 Internal Server Error)
 */
@RequiredArgsConstructor
@Slf4j
@RestControllerAdvice(annotations = {RestController.class})
public class ExceptionAdvice extends ResponseEntityExceptionHandler {

    /**
     * @Valid 검증 실패 시 발생하는 ConstraintViolationException 처리
     * 제약 조건 위반 예외를 공통 응답 포맷으로 변환
     *
     * 주의: constraintViolation.getMessage()는 검증 메시지(예: "이메일 형식이 올바르지 않습니다")를
     * 반환하며, ErrorStatus 상수명과 일치하지 않으므로 항상 _BAD_REQUEST로 처리합니다.
     * 검증 메시지는 응답의 result에 포함됩니다.
     *
     * @param e 제약 조건 위반 예외
     * @param request 웹 요청
     * @return ApiResponse 형식의 에러 응답 (검증 메시지 포함)
     */
    @ExceptionHandler
    public ResponseEntity<Object> validation(ConstraintViolationException e, WebRequest request) {
        // 검증 에러 메시지 수집
        Map<String, String> errors = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(violation -> {
            String propertyPath = violation.getPropertyPath().toString();
            String message = violation.getMessage();
            errors.merge(propertyPath, message, (existing, newMsg) -> existing + ", " + newMsg);
        });

        // ConstraintViolationException은 검증 실패이므로 _BAD_REQUEST로 처리
        return handleExceptionInternalConstraint(e, ErrorStatus._BAD_REQUEST, HttpHeaders.EMPTY, request, errors);
    }

    /**
     * @RequestBody 검증 실패 시 발생하는 MethodArgumentNotValidException 처리
     * 필드별 검증 에러 메시지를 Map으로 수집하여 응답
     *
     * @param e 메서드 인자 검증 실패 예외
     * @param headers HTTP 헤더
     * @param status HTTP 상태 코드
     * @param request 웹 요청
     * @return ApiResponse 형식의 필드 에러 정보 포함 응답
     */
    @Override
    public ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        // 필드별 에러 메시지 수집
        Map<String, String> errors = new LinkedHashMap<>();

        e.getBindingResult().getFieldErrors().stream()
                .forEach(fieldError -> {
                    String fieldName = fieldError.getField();
                    String errorMessage = Optional.ofNullable(fieldError.getDefaultMessage()).orElse("");
                    // 같은 필드에 여러 에러가 있을 경우 ", "로 구분하여 합치기
                    errors.merge(fieldName, errorMessage, (existingErrorMessage, newErrorMessage) -> existingErrorMessage + ", " + newErrorMessage);
                });

        return handleExceptionInternalArgs(e, HttpHeaders.EMPTY, ErrorStatus._BAD_REQUEST, request, errors);
    }

    /**
     * 예상치 못한 모든 예외 처리
     * GeneralException이 아닌 다른 모든 예외를 500 Internal Server Error로 처리
     *
     * @param e 발생한 예외
     * @param request 웹 요청
     * @return ApiResponse 형식의 에러 응답
     */
    @ExceptionHandler
    public ResponseEntity<Object> exception(Exception e, WebRequest request) {
        e.printStackTrace();

        return handleExceptionInternalFalse(e, ErrorStatus._INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY, ErrorStatus._INTERNAL_SERVER_ERROR.getHttpStatus(), request, e.getMessage());
    }

    /**
     * GeneralException 처리
     * 비즈니스 로직에서 명시적으로 throw한 GeneralException을 처리
     *
     * @param generalException GeneralException 인스턴스
     * @param request 웹 요청
     * @return ApiResponse 형식의 에러 응답
     */
    @ExceptionHandler(value = GeneralException.class)
    public ResponseEntity onThrowException(GeneralException generalException, HttpServletRequest request) {
        return handleExceptionInternal(generalException, generalException.getCode(), null, request);
    }

    /**
     * GeneralException 내부 처리
     * BaseCode를 통해 예외 정보를 ApiResponse로 변환
     *
     * @param e 발생한 예외
     * @param code 에러 상태 코드 (BaseCode 구현체)
     * @param headers HTTP 헤더
     * @param request 웹 요청
     * @return ApiResponse 형식의 에러 응답
     */
    private ResponseEntity<Object> handleExceptionInternal(Exception e, BaseCode code,
                                                           HttpHeaders headers, HttpServletRequest request) {

        // BaseCode를 사용하여 실패 응답 생성
        ApiResponse<Object> body = ApiResponse.onFailure(code, null);
//        e.printStackTrace();

        // BaseCode에서 HttpStatus 정보 추출
        ReasonDTO reason = code.getReasonHttpStatus();
        WebRequest webRequest = new ServletWebRequest(request);
        return super.handleExceptionInternal(
                e,
                body,
                headers,
                reason.getHttpStatus(),
                webRequest
        );
    }

    /**
     * 예외 내부 처리 - 에러 상세 정보 포함
     * 예외 메시지를 상세 정보(errorPoint)로 포함하여 응답
     *
     * @param e 발생한 예외
     * @param errorCommonStatus 에러 상태 코드
     * @param headers HTTP 헤더
     * @param status HTTP 상태 코드
     * @param request 웹 요청
     * @param errorPoint 에러 상세 정보 (예: 예외 메시지)
     * @return ApiResponse 형식의 에러 응답
     */
    private ResponseEntity<Object> handleExceptionInternalFalse(Exception e, ErrorStatus errorCommonStatus,
                                                                HttpHeaders headers, HttpStatus status, WebRequest request, String errorPoint) {
        ApiResponse<Object> body = ApiResponse.onFailure(errorCommonStatus, errorPoint);
        return super.handleExceptionInternal(
                e,
                body,
                headers,
                status,
                request
        );
    }

    /**
     * 예외 내부 처리 - 필드별 에러 정보 포함
     * 검증 에러 등에서 여러 필드의 에러 정보를 Map으로 포함하여 응답
     *
     * @param e 발생한 예외
     * @param headers HTTP 헤더
     * @param errorCommonStatus 에러 상태 코드
     * @param request 웹 요청
     * @param errorArgs 필드별 에러 정보 Map
     * @return ApiResponse 형식의 에러 응답
     */
    private ResponseEntity<Object> handleExceptionInternalArgs(Exception e, HttpHeaders headers, ErrorStatus errorCommonStatus,
                                                               WebRequest request, Map<String, String> errorArgs) {
        ApiResponse<Object> body = ApiResponse.onFailure(errorCommonStatus, errorArgs);
        return super.handleExceptionInternal(
                e,
                body,
                headers,
                errorCommonStatus.getHttpStatus(),
                request
        );
    }

    /**
     * 예외 내부 처리 - 제약 조건 위반
     * ConstraintViolationException을 공통 응답 포맷으로 변환 (메시지 포함)
     *
     * @param e 발생한 예외
     * @param errorCommonStatus 에러 상태 코드
     * @param headers HTTP 헤더
     * @param request 웹 요청
     * @param errors 필드별 검증 에러 메시지 Map
     * @return ApiResponse 형식의 에러 응답
     */
    private ResponseEntity<Object> handleExceptionInternalConstraint(Exception e, ErrorStatus errorCommonStatus,
                                                                     HttpHeaders headers, WebRequest request, Map<String, String> errors) {
        ApiResponse<Object> body = ApiResponse.onFailure(errorCommonStatus, errors);
        return super.handleExceptionInternal(
                e,
                body,
                headers,
                errorCommonStatus.getHttpStatus(),
                request
        );
    }

    /**
     * 예외 내부 처리 - 제약 조건 위반
     * ConstraintViolationException을 공통 응답 포맷으로 변환
     *
     * @param e 발생한 예외
     * @param errorCommonStatus 에러 상태 코드
     * @param headers HTTP 헤더
     * @param request 웹 요청
     * @return ApiResponse 형식의 에러 응답
     */
    private ResponseEntity<Object> handleExceptionInternalConstraint(Exception e, ErrorStatus errorCommonStatus,
                                                                     HttpHeaders headers, WebRequest request) {
        ApiResponse<Object> body = ApiResponse.onFailure(errorCommonStatus, null);
        return super.handleExceptionInternal(
                e,
                body,
                headers,
                errorCommonStatus.getHttpStatus(),
                request
        );
    }
}

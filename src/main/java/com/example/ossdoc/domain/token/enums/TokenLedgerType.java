package com.example.ossdoc.domain.token.enums;

public enum TokenLedgerType {
    TOKEN_CHARGE,          // 토큰 충전
    ANALYSIS_USE,          // 일반 분석 차감
    REANALYSIS_USE,        // 재분석 차감
    PAYMENT_CANCEL_REFUND, // 결제 취소/환불로 인한 복구
    ADMIN_ADJUSTMENT       // 관리자 수동 보정
}
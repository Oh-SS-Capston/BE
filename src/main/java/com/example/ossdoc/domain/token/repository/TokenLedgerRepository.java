package com.example.ossdoc.domain.token.repository;

import com.example.ossdoc.domain.token.entity.TokenLedger;
import com.example.ossdoc.domain.token.enums.TokenLedgerType;
import com.example.ossdoc.domain.token.enums.TokenReferenceType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TokenLedgerRepository extends JpaRepository<TokenLedger, Long> {

    List<TokenLedger> findByUser_IdOrderByIdDesc(Long userId, Pageable pageable);

    boolean existsByTypeAndReferenceTypeAndReferenceId(
            TokenLedgerType type,
            TokenReferenceType referenceType,
            String referenceId
    );
}
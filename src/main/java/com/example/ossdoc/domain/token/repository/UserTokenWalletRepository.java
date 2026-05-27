package com.example.ossdoc.domain.token.repository;

import com.example.ossdoc.domain.token.entity.UserTokenWallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface UserTokenWalletRepository extends JpaRepository<UserTokenWallet, Long> {

    Optional<UserTokenWallet> findByUser_Id(Long userId);

    boolean existsByUser_Id(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserTokenWallet w where w.user.id = :userId")
    Optional<UserTokenWallet> findByUserIdForUpdate(@Param("userId") Long userId);
}
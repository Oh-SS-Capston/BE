package com.example.ossdoc.domain.payment.repository;

import com.example.ossdoc.domain.payment.entity.PaymentOrder;
import com.example.ossdoc.domain.payment.enums.PaymentStatus;
import com.example.ossdoc.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByPaymentId(String paymentId);

    Optional<PaymentOrder> findFirstByUserAndStatusOrderByCreatedAtDesc(
            User user,
            PaymentStatus status
    );

    boolean existsByPaymentId(String paymentId);
}
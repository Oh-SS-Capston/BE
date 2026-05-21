package com.example.ossdoc.domain.membership.repository;

import com.example.ossdoc.domain.membership.entity.UserSubscription;
import com.example.ossdoc.domain.membership.enums.SubscriptionStatus;
import com.example.ossdoc.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, Long> {

    Optional<UserSubscription> findFirstByUserAndStatusInOrderByCreatedAtDesc(
            User user,
            Collection<SubscriptionStatus> statuses
    );

    Optional<UserSubscription> findFirstByUserOrderByCreatedAtDesc(User user);

    Optional<UserSubscription> findFirstByLastPaymentId(String lastPaymentId);
}
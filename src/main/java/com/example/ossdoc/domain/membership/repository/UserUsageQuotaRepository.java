package com.example.ossdoc.domain.membership.repository;

import com.example.ossdoc.domain.membership.entity.UserUsageQuota;
import com.example.ossdoc.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserUsageQuotaRepository extends JpaRepository<UserUsageQuota, Long> {

    Optional<UserUsageQuota> findByUser(User user);
}
package com.example.ossdoc.domain.user.repository;

import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    Optional<User> findByEmail(String email);
}
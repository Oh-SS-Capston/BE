package com.example.ossdoc.domain.user.entity;

import com.example.ossdoc.domain.user.enums.AuthProvider;
import com.example.ossdoc.domain.user.enums.UserRole;
import com.example.ossdoc.global.apiPayload.code.BaseAuditedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_provider_provider_id",
                        columnNames = {"provider", "provider_id"}
                ),
                @UniqueConstraint(
                        name = "uk_user_email",
                        columnNames = {"email"}
                ),
                @UniqueConstraint(
                        name = "uk_user_nickname",
                        columnNames = {"nickname"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User extends BaseAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * 회원 탈퇴 시각입니다.
     * 일정 기간 후 재가입 허용 여부 판단에 사용합니다.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 탈퇴 계정이 다시 활성화된 시각입니다.
     */
    @Column(name = "reactivated_at")
    private LocalDateTime reactivatedAt;

    public static User createGoogleUser(
            String providerId,
            String email,
            String nickname
    ) {
        return User.builder()
                .provider(AuthProvider.GOOGLE)
                .providerId(providerId)
                .email(email)
                .name(nickname)
                .nickname(nickname)
                .role(UserRole.USER)
                .active(true)
                .build();
    }

    public void updateGoogleEmail(String email) {
        this.email = email;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
        this.name = nickname;
    }

    public void deactivate(LocalDateTime now) {
        this.active = false;
        this.deletedAt = now;
    }

    public void reactivate(LocalDateTime now) {
        this.active = true;
        this.reactivatedAt = now;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }

    public boolean canReactivateAfter(LocalDateTime now, long rejoinWaitDays) {
        if (isActive()) {
            return true;
        }

        if (deletedAt == null) {
            return false;
        }

        return !deletedAt.plusDays(rejoinWaitDays).isAfter(now);
    }
}
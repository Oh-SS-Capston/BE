package com.example.ossdoc.global.security.oauth;

import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.domain.user.entity.User;
import com.example.ossdoc.domain.user.enums.AuthProvider;
import com.example.ossdoc.domain.user.enums.UserRole;
import com.example.ossdoc.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = super.loadUser(userRequest);

        String providerId = (String) oidcUser.getAttributes().get("sub");
        String email = (String) oidcUser.getAttributes().get("email");
        String name = (String) oidcUser.getAttributes().get("name");

        if (providerId == null || email == null) {
            throw new AuthException(AuthErrorCode.OAUTH2_LOGIN_FAILED);
        }

        User user = userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, providerId)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .provider(AuthProvider.GOOGLE)
                                .providerId(providerId)
                                .email(email)
                                .name(name)
                                .role(UserRole.USER)
                                .active(true)
                                .build()
                ));

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new AuthException(AuthErrorCode.INACTIVE_USER);
        }

        user.updateProfile(email, name);

        return new CustomOAuth2User(user, oidcUser);
    }
}
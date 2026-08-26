package com.example.ossdoc.global.util;

import com.example.ossdoc.domain.auth.exception.code.AuthErrorCode;
import com.example.ossdoc.domain.auth.exception.AuthException;
import com.example.ossdoc.global.security.jwt.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AuthUtil {

    public Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || authentication.getPrincipal() == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUser user) {
            return user.getUserId();
        }

        throw new AuthException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }
}

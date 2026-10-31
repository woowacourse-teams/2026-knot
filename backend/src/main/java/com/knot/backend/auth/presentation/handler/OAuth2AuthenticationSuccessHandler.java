package com.knot.backend.auth.presentation.handler;

import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.auth.application.AuthService;
import com.knot.backend.auth.application.dto.result.AuthLoginResult;
import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.infrastructure.oauth.OAuthLoginUser;
import com.knot.backend.auth.presentation.AuthCookieManager;
import com.knot.backend.global.config.OAuth2LoginProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationSuccessHandler.class);

    private final AuthService authService;
    private final OAuth2LoginProperties loginProperties;
    private final AuthCookieManager authCookieManager;

    public OAuth2AuthenticationSuccessHandler(
            AuthService authService,
            OAuth2LoginProperties loginProperties,
            AuthCookieManager authCookieManager
    ) {
        if (loginProperties == null || loginProperties.getSuccessRedirectUri() == null
                || loginProperties.getSuccessRedirectUri()
                        .isBlank()
                || loginProperties.getNicknameRedirectUri() == null || loginProperties.getNicknameRedirectUri()
                        .isBlank()
                || loginProperties.getFailureRedirectUri() == null || loginProperties.getFailureRedirectUri()
                        .isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH_CONFIGURATION_INVALID);
        }
        this.authService = authService;
        this.loginProperties = loginProperties;
        this.authCookieManager = authCookieManager;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        try {
            OAuthLoginUser loginUser = getLoginUser(authentication);
            AuthLoginResult result = authService.login(loginUser.getOAuthUser());
            authCookieManager.expireLoginCookies(response);

            if (result.requiresNickname()) {
                authCookieManager.addNicknameToken(
                        response,
                        result.token()
                );
                clearAuthentication(request);
                response.sendRedirect(loginProperties.getNicknameRedirectUri());
                return;
            }

            authCookieManager.addRefreshToken(
                    response,
                    result.refreshToken(),
                    result.refreshMaxAge()
            );
            authCookieManager.addAccessToken(
                    response,
                    result.token()
            );
            clearAuthentication(request);
            response.sendRedirect(loginProperties.getSuccessRedirectUri());
        } catch (AuthException exception) {
            logAuthenticationFailure(exception);
            handleFailure(
                    request,
                    response
            );
        } catch (RuntimeException exception) {
            ServerErrorLogger.error(
                    log,
                    "OAuth success processing failed",
                    exception
            );
            handleFailure(
                    request,
                    response
            );
        }
    }

    private void logAuthenticationFailure(AuthException exception) {
        if (ServerErrorLogger.isInternalFailure(exception)) {
            ServerErrorLogger.record(
                    log,
                    "OAuth success processing failed",
                    exception
            );
            return;
        }
        ServerErrorLogger.warn(
                log,
                "OAuth authentication rejected",
                exception
        );
    }

    private void handleFailure(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        clearAuthentication(request);
        authCookieManager.expireLoginCookies(response);
        response.sendRedirect(loginProperties.getFailureRedirectUri());
    }

    private void clearAuthentication(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private OAuthLoginUser getLoginUser(Authentication authentication) {
        if (authentication == null) {
            throw new AuthException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
        }

        try {
            OAuthLoginUser loginUser = (OAuthLoginUser) authentication.getPrincipal();
            if (loginUser == null) {
                throw new AuthException(AuthErrorCode.OAUTH_AUTHENTICATION_FAILED);
            }
            return loginUser;
        } catch (ClassCastException exception) {
            throw new AuthException(
                    AuthErrorCode.OAUTH_AUTHENTICATION_FAILED,
                    exception
            );
        }
    }
}

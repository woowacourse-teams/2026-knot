package com.knot.backend.auth.presentation.handler;

import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.global.config.OAuth2LoginProperties;
import com.knot.backend.auth.presentation.AuthCookieManager;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {
    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationFailureHandler.class);
    private static final String CANCELLED_ERROR = "oauth2_cancelled";

    private final OAuth2LoginProperties loginProperties;
    private final String cancelRedirectUri;
    private final AuthCookieManager cookieManager;

    public OAuth2AuthenticationFailureHandler(
            OAuth2LoginProperties loginProperties,
            AuthCookieManager cookieManager
    ) {
        if (loginProperties == null || loginProperties.getFailureRedirectUri() == null
                || loginProperties.getFailureRedirectUri()
                        .isBlank()) {
            throw new AuthException(AuthErrorCode.OAUTH_CONFIGURATION_INVALID);
        }
        this.loginProperties = loginProperties;
        this.cancelRedirectUri = UriComponentsBuilder.fromUriString(loginProperties.getFailureRedirectUri())
                .replaceQueryParam(
                        "error",
                        CANCELLED_ERROR
                )
                .build()
                .toUriString();
        this.cookieManager = cookieManager;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        logFailure(exception);
        handleFailure(
                request,
                response,
                redirectUri(exception)
        );
    }

    private String redirectUri(AuthenticationException exception) {
        if (isCancelled(exception)) {
            return cancelRedirectUri;
        }
        return loginProperties.getFailureRedirectUri();
    }

    private void logFailure(AuthenticationException exception) {
        if (exception == null) {
            ServerErrorLogger.warn(
                    log,
                    "OAuth authentication rejected",
                    null
            );
            return;
        }
        if (isCancelled(exception) && !ServerErrorLogger.isInternalFailure(exception)) {
            ServerErrorLogger.debug(
                    log,
                    "OAuth authentication cancelled",
                    exception
            );
            return;
        }
        if (ServerErrorLogger.isInternalFailure(exception)) {
            ServerErrorLogger.record(
                    log,
                    "OAuth internal authentication failure",
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

    private boolean isCancelled(AuthenticationException exception) {
        if (!org.springframework.security.oauth2.core.OAuth2AuthenticationException.class.isInstance(exception)) {
            return false;
        }
        org.springframework.security.oauth2.core.OAuth2AuthenticationException failure = org.springframework.security.oauth2.core.OAuth2AuthenticationException.class
                .cast(exception);
        org.springframework.security.oauth2.core.OAuth2Error error = failure.getError();
        return "access_denied".equals(error.getErrorCode());
    }

    public void handleFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            String redirectUri
    ) throws IOException {
        clearAuthentication(request);
        cookieManager.expireLoginCookies(response);
        response.sendRedirect(redirectUri);
    }

    public void clearAuthentication(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}

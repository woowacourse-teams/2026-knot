package com.knot.backend.auth.infrastructure.oauth;

import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.domain.model.OAuthUser;
import java.util.Map;
import java.util.function.Function;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
public class OAuthLoginUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private static final Map<String, Function<Map<String, ?>, OAuthUser>> USER_CONVERTERS = Map.of(
            "github",
            attributes -> GithubUserAttributes.from(attributes)
                    .toOAuthUser(),
            "google",
            attributes -> GoogleUserAttributes.from(attributes)
                    .toOAuthUser(),
            "kakao",
            attributes -> KakaoUserAttributes.from(attributes)
                    .toOAuthUser()
    );

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;

    public OAuthLoginUserService() {
        this(new DefaultOAuth2UserService());
    }

    OAuthLoginUserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        String registrationId = userRequest.getClientRegistration()
                .getRegistrationId();
        Function<Map<String, ?>, OAuthUser> userConverter = USER_CONVERTERS.get(registrationId);
        if (userConverter == null) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("unsupported_provider"),
                    "Unsupported OAuth provider"
            );
        }

        try {
            OAuth2User user = delegate.loadUser(userRequest);
            OAuthUser oauthUser = toOAuthUser(
                    userConverter,
                    user.getAttributes()
            );
            return OAuthLoginUser.of(
                    oauthUser,
                    user
            );
        } catch (OAuth2AuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("user_info_unavailable"),
                    "OAuth 사용자 정보를 조회하지 못했습니다",
                    exception
            );
        }
    }

    private OAuthUser toOAuthUser(
            Function<Map<String, ?>, OAuthUser> userConverter,
            Map<String, ?> attributes
    ) {
        try {
            return userConverter.apply(attributes);
        } catch (AuthException exception) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_user_info"),
                    "OAuth user information is invalid",
                    exception
            );
        }
    }
}

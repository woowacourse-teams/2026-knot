package com.knot.backend.auth.infrastructure.oauth;

import static com.knot.backend.auth.infrastructure.oauth.UserInfoAttributes.optional;
import static com.knot.backend.auth.infrastructure.oauth.UserInfoAttributes.required;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.domain.model.OAuthProvider;
import com.knot.backend.auth.domain.model.OAuthUser;
import java.util.Map;

public record KakaoUserAttributes(
        long id,
        String profileImageUrl
) {

    @SuppressWarnings("unchecked")
    public static KakaoUserAttributes from(Map<String, ?> attributes) {
        if (attributes == null) {
            throw new AuthException(AuthErrorCode.INVALID_OAUTH_USER);
        }

        try {
            Number id = required(
                    attributes,
                    "id",
                    Number.class
            );
            Map<String, ?> account = optional(
                    attributes,
                    "kakao_account",
                    Map.class
            );
            Map<String, ?> profile = optional(
                    account,
                    "profile",
                    Map.class
            );
            return new KakaoUserAttributes(
                    id.longValue(),
                    profileImageUrl(profile)
            );
        } catch (ClassCastException exception) {
            throw new AuthException(
                    AuthErrorCode.INVALID_OAUTH_USER,
                    exception
            );
        }
    }

    // 카카오는 사진을 올리지 않은 사용자에게도 기본 이미지 주소를 주므로 사진 없음으로 본다.
    private static String profileImageUrl(Map<String, ?> profile) {
        Boolean defaultImage = optional(
                profile,
                "is_default_image",
                Boolean.class
        );
        if (Boolean.TRUE.equals(defaultImage)) {
            return null;
        }
        return optional(
                profile,
                "profile_image_url",
                String.class
        );
    }

    public OAuthUser toOAuthUser() {
        return OAuthUser.of(
                OAuthProvider.KAKAO,
                String.valueOf(id),
                profileImageUrl
        );
    }
}

package com.knot.backend.auth.infrastructure.oauth;

import static com.knot.backend.auth.infrastructure.oauth.UserInfoAttributes.optional;
import static com.knot.backend.auth.infrastructure.oauth.UserInfoAttributes.required;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.domain.model.OAuthProvider;
import com.knot.backend.auth.domain.model.OAuthUser;
import java.util.Map;

public record GithubUserAttributes(
        long id,
        String avatarUrl
) {

    public static GithubUserAttributes from(Map<String, ?> attributes) {
        if (attributes == null) {
            throw new AuthException(AuthErrorCode.INVALID_OAUTH_USER);
        }

        try {
            Number id = required(
                    attributes,
                    "id",
                    Number.class
            );
            String avatarUrl = optional(
                    attributes,
                    "avatar_url",
                    String.class
            );
            return new GithubUserAttributes(
                    id.longValue(),
                    avatarUrl
            );
        } catch (ClassCastException exception) {
            throw new AuthException(
                    AuthErrorCode.INVALID_OAUTH_USER,
                    exception
            );
        }
    }

    public OAuthUser toOAuthUser() {
        return OAuthUser.of(
                OAuthProvider.GITHUB,
                String.valueOf(id),
                avatarUrl
        );
    }
}

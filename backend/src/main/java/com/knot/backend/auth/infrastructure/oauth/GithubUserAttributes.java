package com.knot.backend.auth.infrastructure.oauth;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
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

    private static <T> T required(
            Map<String, ?> attributes,
            String name,
            Class<T> type
    ) {
        T value = type.cast(attributes.get(name));
        if (value == null) {
            throw new AuthException(AuthErrorCode.INVALID_OAUTH_USER);
        }
        return value;
    }

    private static <T> T optional(
            Map<String, ?> attributes,
            String name,
            Class<T> type
    ) {
        return type.cast(attributes.get(name));
    }
}

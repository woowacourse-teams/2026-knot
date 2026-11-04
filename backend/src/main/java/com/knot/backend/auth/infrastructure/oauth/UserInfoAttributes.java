package com.knot.backend.auth.infrastructure.oauth;

import com.knot.backend.auth.domain.AuthErrorCode;
import com.knot.backend.auth.domain.AuthException;
import java.util.Map;

final class UserInfoAttributes {

    private UserInfoAttributes() {}

    static <T> T required(
            Map<String, ?> attributes,
            String name,
            Class<T> type
    ) {
        T value = optional(
                attributes,
                name,
                type
        );
        if (value == null) {
            throw new AuthException(AuthErrorCode.INVALID_OAUTH_USER);
        }
        return value;
    }

    static <T> T optional(
            Map<String, ?> attributes,
            String name,
            Class<T> type
    ) {
        if (attributes == null) {
            return null;
        }
        return type.cast(attributes.get(name));
    }
}

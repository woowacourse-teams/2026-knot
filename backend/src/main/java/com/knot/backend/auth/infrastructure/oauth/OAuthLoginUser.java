package com.knot.backend.auth.infrastructure.oauth;

import com.knot.backend.auth.domain.model.OAuthUser;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class OAuthLoginUser implements OAuth2User {
    private final OAuthUser oauthUser;
    private final Collection<? extends GrantedAuthority> authorities;
    private final Map<String, Object> attributes;

    private OAuthLoginUser(
            OAuthUser oauthUser,
            OAuth2User delegate
    ) {
        this.oauthUser = oauthUser;
        this.authorities = List.copyOf(delegate.getAuthorities());
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(delegate.getAttributes()));
    }

    public static OAuthLoginUser of(
            OAuthUser oauthUser,
            OAuth2User delegate
    ) {
        return new OAuthLoginUser(
                oauthUser,
                delegate
        );
    }

    public OAuthUser getOAuthUser() {
        return oauthUser;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public String getName() {
        return String.valueOf(oauthUser.getExternalId());
    }
}

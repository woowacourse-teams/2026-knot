package com.knot.backend.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.knot.backend.auth.domain.model.OAuthUser;
import com.knot.backend.auth.domain.model.OAuthProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

class OAuthLoginUserTest {

    @Test
    @DisplayName("OAuth 로그인 사용자에 도메인 OAuth 정보를 보관한다")
    void create_success() {
        // given
        OAuthUser oauthUser = OAuthUser.of(
                OAuthProvider.GITHUB,
                "42",
                "https://example.com/avatar"
        );
        OAuth2User delegate = mock(OAuth2User.class);
        GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_USER");
        doReturn(List.of(authority)).when(delegate)
                .getAuthorities();
        doReturn(
                Map.of(
                        "id",
                        42L
                )
        ).when(delegate)
                .getAttributes();

        // when
        OAuthLoginUser result = OAuthLoginUser.of(
                oauthUser,
                delegate
        );

        // then
        assertThat(result.getOAuthUser()).isEqualTo(oauthUser);
        assertThat(result.getName()).isEqualTo("42");
        assertThat(result.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
        assertThat(result.getAttributes()).containsEntry(
                "id",
                42L
        );
    }
}

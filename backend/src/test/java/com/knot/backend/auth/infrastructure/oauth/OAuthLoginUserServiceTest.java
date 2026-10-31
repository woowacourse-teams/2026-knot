package com.knot.backend.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.domain.model.OAuthProvider;
import com.knot.backend.auth.domain.model.OAuthUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;

@SuppressWarnings("unchecked")
class OAuthLoginUserServiceTest {

    @Test
    @DisplayName("GitHub OAuth 사용자 응답을 인증 사용자로 변환한다")
    void loadUser_success() {
        // given
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
        OAuthLoginUserService service = new OAuthLoginUserService(delegate);
        OAuth2UserRequest request = request("github");
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttributes()).thenReturn(
                Map.of(
                        "id",
                        42L,
                        "avatar_url",
                        "https://example.com/avatar"
                )
        );
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(user)
                .getAuthorities();
        when(delegate.loadUser(request)).thenReturn(user);

        // when
        OAuth2User result = service.loadUser(request);

        // then
        assertThat(result).isExactlyInstanceOf(OAuthLoginUser.class);
        OAuthLoginUser loginUser = OAuthLoginUser.class.cast(result);
        assertThat(loginUser.getOAuthUser()).isEqualTo(
                OAuthUser.of(
                        OAuthProvider.GITHUB,
                        "42",
                        "https://example.com/avatar"
                )
        );
    }

    @Test
    @DisplayName("Google OAuth 사용자 응답을 인증 사용자로 변환한다")
    void loadUser_success_google() {
        // given
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
        OAuthLoginUserService service = new OAuthLoginUserService(delegate);
        OAuth2UserRequest request = request("google");
        OAuth2User user = user(
                Map.of(
                        "sub",
                        "google-42",
                        "picture",
                        "https://example.com/google.png"
                )
        );
        when(delegate.loadUser(request)).thenReturn(user);

        // when
        OAuth2User result = service.loadUser(request);

        // then
        assertThat(
                OAuthLoginUser.class.cast(result)
                        .getOAuthUser()
        ).isEqualTo(
                OAuthUser.of(
                        OAuthProvider.GOOGLE,
                        "google-42",
                        "https://example.com/google.png"
                )
        );
    }

    @Test
    @DisplayName("카카오 OAuth 사용자 응답을 인증 사용자로 변환한다")
    void loadUser_success_kakao() {
        // given
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
        OAuthLoginUserService service = new OAuthLoginUserService(delegate);
        OAuth2UserRequest request = request("kakao");
        OAuth2User user = user(
                Map.of(
                        "id",
                        4242L,
                        "kakao_account",
                        Map.of(
                                "profile",
                                Map.of(
                                        "profile_image_url",
                                        "https://example.com/kakao.png",
                                        "is_default_image",
                                        false
                                )
                        )
                )
        );
        when(delegate.loadUser(request)).thenReturn(user);

        // when
        OAuth2User result = service.loadUser(request);

        // then
        assertThat(
                OAuthLoginUser.class.cast(result)
                        .getOAuthUser()
        ).isEqualTo(
                OAuthUser.of(
                        OAuthProvider.KAKAO,
                        "4242",
                        "https://example.com/kakao.png"
                )
        );
    }

    @Test
    @DisplayName("지원하지 않는 OAuth provider는 인증 예외를 발생시킨다")
    void loadUser_failure_unsupportedProvider() {
        // given
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
        OAuthLoginUserService service = new OAuthLoginUserService(delegate);

        // when
        Throwable thrown = catchThrowable(() -> service.loadUser(request("naver")));

        // then
        assertThat(thrown).isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("잘못된 GitHub OAuth 응답은 인증 예외 원인으로 보존한다")
    void loadUser_failure_invalidUserInfo() {
        // given
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
        OAuthLoginUserService service = new OAuthLoginUserService(delegate);
        OAuth2User user = mock(OAuth2User.class);
        OAuth2UserRequest request = request("github");
        when(user.getAttributes()).thenReturn(
                Map.of(
                        "id",
                        "not-a-number"
                )
        );
        when(delegate.loadUser(request)).thenReturn(user);

        // when
        Throwable thrown = catchThrowable(() -> service.loadUser(request));

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                OAuth2AuthenticationException.class,
                exception -> assertThat(exception.getCause()).isInstanceOf(AuthException.class)
        );
    }

    @Test
    @DisplayName("OAuth 사용자 정보 조회가 실패하면 OAuth 인증 예외로 변환한다")
    void loadUser_failure_externalService() {
        // given
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
        OAuthLoginUserService service = new OAuthLoginUserService(delegate);
        OAuth2UserRequest request = request("github");
        when(delegate.loadUser(request)).thenThrow(new IllegalStateException());

        // when
        Throwable thrown = catchThrowable(() -> service.loadUser(request));

        // then
        assertThat(thrown).isInstanceOfSatisfying(
                OAuth2AuthenticationException.class,
                exception -> assertThat(
                        exception.getError()
                                .getErrorCode()
                ).isEqualTo("user_info_unavailable")
        );
    }

    private OAuth2User user(Map<String, Object> attributes) {
        OAuth2User user = mock(OAuth2User.class);
        when(user.getAttributes()).thenReturn(attributes);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_USER"))).when(user)
                .getAuthorities();
        return user;
    }

    private OAuth2UserRequest request(String registrationId) {
        ClientRegistration registration = mock(ClientRegistration.class);
        when(registration.getRegistrationId()).thenReturn(registrationId);
        OAuth2UserRequest request = mock(OAuth2UserRequest.class);
        when(request.getClientRegistration()).thenReturn(registration);
        return request;
    }
}

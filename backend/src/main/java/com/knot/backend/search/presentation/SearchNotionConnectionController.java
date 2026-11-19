package com.knot.backend.search.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.global.config.JwtProperties;
import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.search.application.notion.NotionAuthorizationStart;
import com.knot.backend.search.application.notion.NotionConnectionCallback;
import com.knot.backend.search.application.notion.NotionConnectionCallbackResult;
import com.knot.backend.search.application.notion.NotionConnectionCallbackService;
import com.knot.backend.search.application.notion.NotionConnectionService;
import com.knot.backend.search.presentation.dto.request.SearchNotionAuthorizationRequest;
import com.knot.backend.search.presentation.dto.response.SearchNotionAuthorizationResponse;
import com.knot.backend.search.presentation.dto.response.SearchNotionConnectionResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequiredArgsConstructor
public class SearchNotionConnectionController implements SearchNotionConnectionApi {
    private static final String CALLBACK_PATH = "/api/v1/search/notion-connection/callback";
    private static final String CONNECTION_PATH = "/api/v1/workspaces/{workspaceId}/search/notion-connection";
    static final String STATE_COOKIE = "KNOT_NOTION_CONNECTION_STATE";

    private final NotionConnectionService connections;
    private final NotionConnectionCallbackService callbacks;
    private final SearchNotionProperties properties;
    private final JwtProperties jwtProperties;

    @Override
    @GetMapping(CONNECTION_PATH)
    public SearchNotionConnectionResponse find(
            @PathVariable Long workspaceId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return SearchNotionConnectionResponse.from(
                connections.find(
                        workspaceId,
                        authenticatedMember.getMemberId()
                )
        );
    }

    @Override
    @PostMapping(value = CONNECTION_PATH + "/authorization", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SearchNotionAuthorizationResponse startAuthorization(
            @PathVariable Long workspaceId,
            @Valid @RequestBody SearchNotionAuthorizationRequest request,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember,
            HttpServletResponse response
    ) {
        NotionAuthorizationStart start = connections.startAuthorization(
                workspaceId,
                authenticatedMember.getMemberId()
        );
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                stateCookie(
                        start.state(),
                        properties.getAuthorizationStateTtl()
                ).toString()
        );
        return new SearchNotionAuthorizationResponse(start.authorizationUrl());
    }

    @Override
    @DeleteMapping(CONNECTION_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(
            @PathVariable Long workspaceId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        connections.disconnect(
                workspaceId,
                authenticatedMember.getMemberId()
        );
    }

    @Override
    @GetMapping(CALLBACK_PATH)
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String iss,
            @CookieValue(name = STATE_COOKIE, required = false) String browserState
    ) {
        NotionConnectionCallbackResult result = callbacks.complete(
                new NotionConnectionCallback(
                        state,
                        code,
                        error,
                        iss,
                        browserState
                )
        );
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirect(result))
                .header(
                        HttpHeaders.SET_COOKIE,
                        stateCookie(
                                "",
                                Duration.ZERO
                        ).toString()
                )
                .build();
    }

    /**
     * 노션이 브라우저를 돌려보내는 요청(다른 사이트에서 온 최상위 이동)에도 실리도록 SameSite=Lax로 두고 callback 경로에만
     * 보낸다.
     */
    private ResponseCookie stateCookie(
            String value,
            Duration maxAge
    ) {
        return ResponseCookie.from(
                STATE_COOKIE,
                value
        )
                .httpOnly(true)
                .secure(jwtProperties.isSecure())
                .sameSite("Lax")
                .path(CALLBACK_PATH)
                .maxAge(maxAge)
                .build();
    }

    /**
     * 화면 주소에는 notionConnection 쿼리만 붙인다. 오류 설명과 토큰은 싣지 않는다.
     */
    private URI redirect(NotionConnectionCallbackResult result) {
        if (!result.hasWorkspace()) {
            return URI.create(properties.getConnectionFallbackUrl());
        }
        return UriComponentsBuilder.fromUriString(properties.getConnectionResultUrl())
                .queryParam(
                        "notionConnection",
                        result.outcome()
                                .name()
                )
                .buildAndExpand(result.workspaceId())
                .encode()
                .toUri();
    }
}

package com.knot.backend.search.application.notion;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.search.domain.model.notion.NotionAuthorizationState;
import com.knot.backend.search.domain.model.notion.NotionConnectionGrant;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 노션 동의 화면에서 돌아온 콜백을 state 하나로 처리한다. 로그인 쿠키는 보지 않고, state에 묶인 Member가 지금도
 * Workspace 멤버인지 다시 확인한다. 어떤 실패도 화면으로 돌려보낼 결과 값으로 바꾼다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotionConnectionCallbackService {
    private static final String ACCESS_DENIED = "access_denied";

    private final NotionConnectionWriter writer;
    private final SearchNotionAuthorizationServer authorizationServer;
    private final SearchNotionTokenCipher cipher;
    private final SearchNotionProperties properties;
    private final Clock clock;

    public NotionConnectionCallbackResult complete(NotionConnectionCallback callback) {
        if (isBlank(callback.state())) {
            return NotionConnectionCallbackResult.unknownState();
        }
        Optional<NotionAuthorizationState> consumed = writer.consume(NotionOAuthSecrets.hash(callback.state()));
        if (consumed.isEmpty()) {
            log.info("Search notion connection callback outcome=unknown_state");
            return NotionConnectionCallbackResult.unknownState();
        }
        NotionAuthorizationState state = consumed.get();
        NotionConnectionOutcome outcome = outcomeSafely(
                state,
                callback
        );
        log.info(
                "Search notion connection callback workspaceId={} outcome={}",
                state.getWorkspaceId(),
                outcome
        );
        return new NotionConnectionCallbackResult(
                state.getWorkspaceId(),
                outcome
        );
    }

    private NotionConnectionOutcome outcomeSafely(
            NotionAuthorizationState state,
            NotionConnectionCallback callback
    ) {
        try {
            return outcome(
                    state,
                    callback
            );
        } catch (RuntimeException exception) {
            ServerErrorLogger.error(
                    log,
                    "Search notion connection callback failed",
                    exception
            );
            return NotionConnectionOutcome.FAILED;
        }
    }

    private NotionConnectionOutcome outcome(
            NotionAuthorizationState state,
            NotionConnectionCallback callback
    ) {
        if (!callback.state()
                .equals(callback.browserState())) {
            // 다른 브라우저가 동의 화면 주소를 열면 그 사람의 노션이 시작한 Member의 Workspace에 붙지 않게 막는다.
            return NotionConnectionOutcome.FAILED;
        }
        if (state.isExpired(now()) || isForeignIssuer(callback.issuer())) {
            return NotionConnectionOutcome.FAILED;
        }
        if (ACCESS_DENIED.equals(callback.error())) {
            return NotionConnectionOutcome.CANCELLED;
        }
        if (!isBlank(callback.error()) || isBlank(callback.code())) {
            return NotionConnectionOutcome.FAILED;
        }
        Optional<NotionConnectionOutcome> rejected = writer.rejectBeforeExchange(
                state.getWorkspaceId(),
                state.getMemberId()
        );
        if (rejected.isPresent()) {
            return rejected.get();
        }
        return exchange(
                state,
                callback.code()
        ).map(
                grant -> save(
                        state,
                        grant
                )
        )
                .orElse(NotionConnectionOutcome.FAILED);
    }

    private Optional<SearchNotionIssuedGrant> exchange(
            NotionAuthorizationState state,
            String code
    ) {
        try {
            return Optional.of(
                    authorizationServer.exchange(
                            code,
                            cipher.decrypt(state.getEncryptedCodeVerifier())
                    )
            );
        } catch (SearchNotionException exception) {
            ServerErrorLogger.warn(
                    log,
                    "Search notion token exchange failed",
                    exception
            );
            return Optional.empty();
        }
    }

    private NotionConnectionOutcome save(
            NotionAuthorizationState state,
            SearchNotionIssuedGrant issued
    ) {
        NotionConnectionGrant grant = new NotionConnectionGrant(
                issued.notionWorkspaceId(),
                issued.notionWorkspaceName(),
                issued.notionWorkspaceIcon(),
                cipher.encrypt(issued.accessToken()),
                encryptNullable(issued.refreshToken()),
                issued.accessTokenExpiresAt()
        );
        try {
            return writer.save(
                    state.getWorkspaceId(),
                    state.getMemberId(),
                    grant,
                    now()
            );
        } catch (DataIntegrityViolationException exception) {
            return NotionConnectionOutcome.NOTION_CONNECTION_ALREADY_CONNECTED;
        }
    }

    private String encryptNullable(String value) {
        if (isBlank(value)) {
            return null;
        }
        return cipher.encrypt(value);
    }

    private boolean isForeignIssuer(String issuer) {
        return !isBlank(issuer) && !issuer.equals(properties.getIssuer());
    }

    private Instant now() {
        return clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

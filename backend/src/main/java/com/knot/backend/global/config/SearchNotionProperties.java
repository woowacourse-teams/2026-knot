package com.knot.backend.global.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 탐색 노션 연결 설정. 정본에 미정으로 남은 값(읽기와 위치 검색 시간 한도, 자르는 길이)은 여기서만 정한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "knot.search.notion")
public class SearchNotionProperties {

    private String mcpUrl = "https://mcp.notion.com/mcp";
    private String issuer = "https://mcp.notion.com";
    private String authorizationEndpoint = "https://mcp.notion.com/authorize";
    private String tokenEndpoint = "https://mcp.notion.com/token";
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String connectionResultUrl = "/workspace/{workspaceId}/apps";
    private String connectionFallbackUrl = "/workspace";
    private String tokenEncryptionKey;
    private Duration authorizationStateTtl = Duration.ofMinutes(10);
    private Duration readTimeout = Duration.ofSeconds(8);
    private Duration locationTimeout = Duration.ofSeconds(8);
    private int searchPageSize = 10;
    // 검색 한 번의 기본 페이지 수(10)를 모두 답변 입력 한도(48000자)의 절반 안에 담는 길이다.
    private int pageContentCharacterLimit = 2000;
    private Duration createTimeout = Duration.ofSeconds(10);
    private Duration rateLimitMaxWait = Duration.ofSeconds(10);

    /**
     * 이보다 오래 실행 중인 쓰기 확인 카드는 서버가 실행 중에 멈춘 것으로 본다. 살아 있는 실행이 가장 오래 걸리는 시간(DB 구조 읽기와
     * 만들기를 각각 한도 안에서 기다렸다가 한 번 더 부름)보다 길다. 실행 API와 주기 정리가 같은 값을 쓴다.
     */
    public Duration runningCardAbandonedAfter() {
        return readTimeout.plus(createTimeout)
                .plus(rateLimitMaxWait)
                .multipliedBy(2)
                .plus(Duration.ofSeconds(5));
    }
}

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
    private int pageContentCharacterLimit = 8000;
    private Duration createTimeout = Duration.ofSeconds(10);
    private Duration rateLimitMaxWait = Duration.ofSeconds(10);
}

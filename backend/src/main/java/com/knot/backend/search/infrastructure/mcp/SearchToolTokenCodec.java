package com.knot.backend.search.infrastructure.mcp;

import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.SearchToolTokens;
import com.knot.backend.search.domain.SearchException;
import com.knot.backend.search.domain.SearchErrorCode;
import com.knot.backend.search.application.dto.query.SearchDocumentParameters;
import com.knot.backend.search.domain.SearchToolException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class SearchToolTokenCodec implements SearchToolTokens {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder()
            .withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper mapper;
    private final SearchAgentProperties properties;
    private final Clock clock;

    public SearchToolTokenCodec(
            ObjectMapper mapper,
            SearchAgentProperties properties,
            Clock clock
    ) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    public String encode(
            String purpose,
            Object value
    ) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put(
                "audience",
                purpose
        );
        payload.set(
                "payload",
                mapper.valueToTree(value)
        );
        String body = ENCODER.encodeToString(mapper.writeValueAsBytes(payload));
        return body + "." + ENCODER.encodeToString(sign(body));
    }

    public <T> T decode(
            String purpose,
            String encoded,
            Class<T> type
    ) {
        try {
            if (encoded == null || encoded.length() > 8192) {
                throw invalid();
            }
            String[] parts = encoded.split(
                    "\\.",
                    -1
            );
            if (parts.length != 2) {
                throw invalid();
            }
            if (!MessageDigest.isEqual(
                    sign(parts[0]),
                    DECODER.decode(parts[1])
            )) {
                throw invalid();
            }
            JsonNode payload = mapper.readTree(DECODER.decode(parts[0]));
            if (!purpose.equals(
                    payload.path("audience")
                            .asString()
            )) {
                throw invalid();
            }
            return mapper.treeToValue(
                    payload.path("payload"),
                    type
            );
        } catch (SearchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid(exception);
        }
    }

    public SearchToolScope authenticate(String bearer) {
        SearchToolScope scope = decode(
                "knot-search-mcp",
                bearer,
                SearchToolScope.class
        );
        if (!clock.instant()
                .isBefore(scope.expiresAt())) {
            throw new SearchToolException("SEARCH_ACCESS_DENIED");
        }
        return scope;
    }

    public String binding(
            SearchToolScope scope,
            SearchDocumentParameters parameters,
            String purpose
    ) {
        return digest(
                mapper.writeValueAsBytes(
                        List.of(
                                scope,
                                parameters.withoutCursor(),
                                purpose
                        )
                )
        );
    }

    public String revision(String content) {
        return com.knot.backend.search.domain.model.SearchEvidenceVersion.contentRevision(content);
    }

    private String digest(byte[] bytes) {
        try {
            return ENCODER.encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
            );
        } catch (GeneralSecurityException exception) {
            throw new SearchException(
                    SearchErrorCode.INVALID_SEARCH_CONFIGURATION,
                    exception
            );
        }
    }

    private byte[] sign(String text) {
        String key = properties.getSigningKey();
        if (key == null || key.length() < 32) {
            throw new SearchException(SearchErrorCode.INVALID_SEARCH_CONFIGURATION);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            key.getBytes(StandardCharsets.UTF_8),
                            "HmacSHA256"
                    )
            );
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new SearchException(
                    SearchErrorCode.INVALID_SEARCH_CONFIGURATION,
                    exception
            );
        }
    }

    private SearchToolException invalid() {
        return new SearchToolException("INVALID_TOOL_ARGUMENTS");
    }

    private SearchToolException invalid(Throwable cause) {
        return new SearchToolException(
                "INVALID_TOOL_ARGUMENTS",
                cause
        );
    }
}

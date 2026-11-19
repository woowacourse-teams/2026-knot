package com.knot.backend.search.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class SearchEvidenceVersion {

    private SearchEvidenceVersion() {}

    public static String fromDocument(
            String title,
            String topic,
            String content
    ) {
        return fromContentRevision(
                title,
                topic,
                contentRevision(content)
        );
    }

    public static String fromContentRevision(
            String title,
            String topic,
            String revision
    ) {
        return contentRevision(title.length() + ":" + title + topic.length() + ":" + topic + revision);
    }

    public static String contentRevision(String content) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8))
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }
}

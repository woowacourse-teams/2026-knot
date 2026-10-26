package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.regex.Pattern;

public final class RecordingTitle {
    public static final int MAX_LENGTH = 100;
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{javaWhitespace}\\p{Z}]+");
    private static final String FALLBACK_SUFFIX = " 님의 녹음";

    private RecordingTitle() {}

    public static String normalize(String generated) {
        if (generated == null) {
            throw invalid();
        }
        String title = WHITESPACE.matcher(generated)
                .replaceAll(" ")
                .strip();
        if (title.isEmpty() || title.length() > MAX_LENGTH) {
            throw invalid();
        }
        return title;
    }

    public static String display(
            String aiTitle,
            String starterNickname
    ) {
        if (aiTitle != null) {
            return aiTitle;
        }
        return starterNickname + FALLBACK_SUFFIX;
    }

    private static RecordingException invalid() {
        return new RecordingException(RecordingErrorCode.INVALID_RECORDING_TITLE);
    }
}

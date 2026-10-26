package com.knot.backend.recording.domain.model;

import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import java.util.regex.Pattern;

public final class MeetingSummaryContent {
    private static final String CONCLUSION = "회의 핵심 결론:";
    private static final Pattern OUTER_WHITESPACE = Pattern
            .compile("^[\\p{javaWhitespace}\\p{Z}]+|[\\p{javaWhitespace}\\p{Z}]+$");

    private MeetingSummaryContent() {}

    public static String normalize(String value) {
        if (value == null) {
            throw new RecordingException(RecordingErrorCode.INVALID_MEETING_SUMMARY);
        }
        String content = OUTER_WHITESPACE.matcher(value)
                .replaceAll("");
        int end = content.indexOf('\n');
        String first = content;
        if (end >= 0) {
            first = content.substring(
                    0,
                    end
            )
                    .stripTrailing();
        }
        if (!first.equals("## 한눈에 보기") || content.length() <= first.length()) {
            throw new RecordingException(RecordingErrorCode.INVALID_MEETING_SUMMARY);
        }
        validateConclusion(content);
        return content;
    }
    private static void validateConclusion(String content) {
        String lastLine = content.substring(content.lastIndexOf('\n') + 1);
        if (!lastLine.startsWith(CONCLUSION) || lastLine.length() <= CONCLUSION.length()) {
            throw new RecordingException(RecordingErrorCode.INVALID_MEETING_SUMMARY);
        }
    }

}

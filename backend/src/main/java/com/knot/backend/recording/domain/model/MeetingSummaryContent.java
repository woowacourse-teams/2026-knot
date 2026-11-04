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
        String content = OUTER_WHITESPACE.matcher(
                value.replace(
                        "\r\n",
                        "\n"
                )
        )
                .replaceAll("");
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        content = restoreJoinedLines(content);
        if (content.startsWith("# 한눈에 보기\n")) {
            content = "#" + content;
        }
        content = normalizeConclusion(content);
        if (!hasBody(content)) {
            throw new RecordingException(RecordingErrorCode.INVALID_MEETING_SUMMARY);
        }
        return content;
    }

    private static boolean hasBody(String content) {
        for (String line : content.split("\\R")) {
            String text = OUTER_WHITESPACE.matcher(line)
                    .replaceAll("");
            if (text.startsWith("#")) {
                continue;
            }
            if (text.startsWith(CONCLUSION)) {
                text = OUTER_WHITESPACE.matcher(text.substring(CONCLUSION.length()))
                        .replaceAll("");
            }
            if (text.codePoints()
                    .anyMatch(Character::isLetterOrDigit)) {
                return true;
            }
        }
        return false;
    }

    private static String restoreJoinedLines(String content) {
        if (content.contains("\n") || !content.startsWith("## 한눈에 보기### ") || content.contains("```")) {
            return content;
        }
        int conclusion = content.indexOf(CONCLUSION);
        if (conclusion < 0 || conclusion != content.lastIndexOf(CONCLUSION)) {
            return content;
        }
        String body = content.substring(
                0,
                conclusion
        )
                .replaceAll(
                        "(?=### (?:정한 방향|아직 결정하지 못한 것)|## [0-9]+\\. )",
                        "\n"
                );
        body = body.replaceAll(
                "(?m)^(#{2,3} [^\\n]*?)(?=- )",
                "$1\n"
        )
                .replaceAll(
                        "(?<=\\.)(?=- )",
                        "\n"
                )
                .replaceAll(
                        "(?<=\\.)(?=적용 방향:|정리:)",
                        "\n"
                );
        return body + "\n" + content.substring(conclusion);
    }

    private static String normalizeConclusion(String content) {
        int lastLineStart = content.lastIndexOf('\n') + 1;
        String lastLine = content.substring(lastLineStart);
        String formatted = "- **회의 핵심 결론:**";
        if (!lastLine.startsWith(formatted)) {
            return content;
        }
        return content.substring(
                0,
                lastLineStart
        ) + CONCLUSION + lastLine.substring(formatted.length());
    }

}

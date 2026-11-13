package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.domain.model.DocumentText;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class DocumentMarkdownSectionReader {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}##[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private final Map<String, StringBuilder> sections = new LinkedHashMap<>();
    private final Map<String, Integer> sectionOffsets = new LinkedHashMap<>();
    private StringBuilder body;
    private String fence;

    Map<String, StringBuilder> read(String content) {
        validateOpeningHeading(content);
        Matcher lineBreak = LINE_BREAK.matcher(content);
        int lineStart = 0;
        while (lineBreak.find()) {
            readLine(
                    content.substring(
                            lineStart,
                            lineBreak.start()
                    ),
                    lineStart
            );
            lineStart = lineBreak.end();
        }
        readLine(
                content.substring(lineStart),
                lineStart
        );
        validateClosedFence();
        return sections;
    }

    Map<String, String> readBlocks(String content) {
        read(content);
        List<String> names = List.copyOf(sectionOffsets.keySet());
        Map<String, String> blocks = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            int start = sectionOffsets.get(name);
            int end = sectionEnd(
                    names,
                    index,
                    content.length()
            );
            blocks.put(
                    name,
                    content.substring(
                            start,
                            end
                    )
            );
        }
        return blocks;
    }

    private int sectionEnd(
            List<String> names,
            int index,
            int contentLength
    ) {
        if (index + 1 == names.size()) {
            return contentLength;
        }
        String nextName = names.get(index + 1);
        return sectionOffsets.get(nextName);
    }

    private void readLine(
            String line,
            int lineStart
    ) {
        Matcher marker = FENCE.matcher(line);
        if (marker.matches()) {
            updateFence(marker);
            appendBody(line);
            return;
        }
        if (fence != null) {
            appendBody(line);
            return;
        }
        Matcher heading = HEADING.matcher(line);
        if (heading.matches()) {
            startSection(
                    heading,
                    lineStart
            );
            return;
        }
        validateBodyHeading(line);
        appendBody(line);
    }

    private void startSection(
            Matcher heading,
            int lineStart
    ) {
        String name = heading.group(1);
        name = name.strip();
        validateUniqueSection(name);
        sectionOffsets.put(
                name,
                lineStart
        );
        body = new StringBuilder();
        sections.put(
                name,
                body
        );
    }

    private void updateFence(Matcher matcher) {
        String marker = matcher.group(1);
        if (fence == null) {
            fence = marker;
            return;
        }
        String trailing = matcher.group(2);
        if (isClosingFence(
                marker,
                trailing
        )) {
            fence = null;
        }
    }

    private boolean isClosingFence(
            String marker,
            String trailing
    ) {
        char markerType = marker.charAt(0);
        char fenceType = fence.charAt(0);
        if (markerType != fenceType) {
            return false;
        }
        int fenceLength = fence.length();
        if (marker.length() < fenceLength) {
            return false;
        }
        return DocumentText.isBlank(trailing);
    }

    private void appendBody(String line) {
        if (body == null) {
            validatePreamble(line);
            return;
        }
        body.append(line);
        body.append('\n');
    }

    private void validateOpeningHeading(String content) {
        if (!content.startsWith("## 핵심 요약")) {
            throw invalidResponse();
        }
    }

    private void validateUniqueSection(String name) {
        if (sections.containsKey(name)) {
            throw invalidResponse();
        }
    }

    private void validateBodyHeading(String line) {
        if (line.matches("^ {0,3}#[ \\t]+.*")) {
            throw invalidResponse();
        }
    }

    private void validatePreamble(String line) {
        if (!DocumentText.isBlank(line)) {
            throw invalidResponse();
        }
    }

    private void validateClosedFence() {
        if (fence != null) {
            throw invalidResponse();
        }
    }

    private DocumentException invalidResponse() {
        return new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE);
    }
}

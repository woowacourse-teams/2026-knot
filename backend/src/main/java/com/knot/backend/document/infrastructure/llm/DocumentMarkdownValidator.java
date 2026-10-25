package com.knot.backend.document.infrastructure.llm;

import com.knot.backend.document.domain.model.DocumentText;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "knot.llm", name = "enabled", havingValue = "true")
public class DocumentMarkdownValidator {
    private static final List<String> FIXED_SECTIONS = List.of(
            "결정",
            "보류",
            "미결정",
            "할 일"
    );
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern LINKS = Pattern.compile(
            "(?i)(?:https?://|mailto:|www\\.|!?\\[[^\\]\\n]*\\]\\s*(?:\\(|\\[)|^ {0,3}\\[[^\\]]+\\]:|<(?:a|img)\\b)",
            Pattern.MULTILINE
    );
    private static final Pattern PLACEHOLDER = Pattern.compile("^(?:없음|해당없음|내용없음)[.!。]?$");

    public DocumentGenerationResult normalizeAndValidate(DocumentGenerationResult result) {
        DocumentMarkdownSectionOrderNormalizer normalizer = new DocumentMarkdownSectionOrderNormalizer();
        String content = normalizer.normalize(
                result.content(),
                FIXED_SECTIONS
        );
        DocumentGenerationResult normalized = new DocumentGenerationResult(
                result.title(),
                result.summary(),
                content
        );
        validate(normalized);
        return normalized;
    }

    public void validate(DocumentGenerationResult result) {
        validateLinks(result.title());
        validateLinks(result.summary());
        validateLinks(result.content());
        DocumentMarkdownSectionReader reader = new DocumentMarkdownSectionReader();
        String content = result.content();
        Map<String, StringBuilder> sections = reader.read(content);
        validateSummarySection(sections);
        validateSectionOrder(sections);
        validateSectionBodies(sections);
    }

    private void validateSectionBodies(Map<String, StringBuilder> sections) {
        for (StringBuilder body : sections.values()) {
            String text = body.toString();
            validateSectionBody(text);
        }
    }

    private void validateSummarySection(Map<String, StringBuilder> sections) {
        Set<String> sectionNames = sections.keySet();
        List<String> names = List.copyOf(sectionNames);
        if (names.isEmpty()) {
            throw invalidResponse();
        }
        String firstName = names.getFirst();
        if (!firstName.equals("핵심 요약")) {
            throw invalidResponse();
        }
    }

    private void validateSectionOrder(Map<String, StringBuilder> sections) {
        int previous = -1;
        boolean additionalSectionStarted = false;
        for (String name : sections.keySet()) {
            if (name.equals("핵심 요약")) {
                continue;
            }
            int index = FIXED_SECTIONS.indexOf(name);
            if (index < 0) {
                additionalSectionStarted = true;
                continue;
            }
            if (additionalSectionStarted || index <= previous) {
                throw invalidResponse();
            }
            previous = index;
        }
    }

    private void validateSectionBody(String body) {
        if (body.lines()
                .noneMatch(this::isBodyLine)) {
            throw invalidResponse();
        }
        boolean onlyPlaceholders = body.lines()
                .filter(this::isBodyLine)
                .map(String::strip)
                .map(
                        line -> line.replaceFirst(
                                "^(?:[-*+] |[0-9]+[.)] )",
                                ""
                        )
                )
                .map(
                        line -> line.replaceAll(
                                "[()*_`\\p{Z}]",
                                ""
                        )
                )
                .allMatch(
                        line -> PLACEHOLDER.matcher(line)
                                .matches()
                );
        if (onlyPlaceholders) {
            throw invalidResponse();
        }
    }

    private boolean isBodyLine(String line) {
        if (DocumentText.isBlank(line)) {
            return false;
        }
        if (FENCE.matcher(line)
                .matches()) {
            return false;
        }
        return !line.matches("^ {0,3}#{1,6}[ \\t]+.*|^\\s*[-*+]\\s*$");
    }

    private void validateLinks(String value) {
        if (value == null) {
            return;
        }
        if (LINKS.matcher(value)
                .find()) {
            throw invalidResponse();
        }
    }

    private DocumentException invalidResponse() {
        return new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_GENERATION_RESPONSE);
    }
}

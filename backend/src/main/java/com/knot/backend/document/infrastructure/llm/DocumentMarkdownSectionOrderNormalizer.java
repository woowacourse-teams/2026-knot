package com.knot.backend.document.infrastructure.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class DocumentMarkdownSectionOrderNormalizer {

    String normalize(
            String content,
            List<String> fixedSections
    ) {
        DocumentMarkdownSectionReader reader = new DocumentMarkdownSectionReader();
        Map<String, String> sections = reader.readBlocks(content);
        Map<String, String> ordered = new LinkedHashMap<>();
        appendPresentSection(
                ordered,
                sections,
                "핵심 요약"
        );
        for (String name : fixedSections) {
            appendPresentSection(
                    ordered,
                    sections,
                    name
            );
        }
        sections.forEach(ordered::putIfAbsent);
        List<String> originalNames = List.copyOf(sections.keySet());
        List<String> orderedNames = List.copyOf(ordered.keySet());
        if (originalNames.equals(orderedNames)) {
            return content;
        }
        return joinBlocks(ordered);
    }

    private void appendPresentSection(
            Map<String, String> ordered,
            Map<String, String> sections,
            String name
    ) {
        if (sections.containsKey(name)) {
            ordered.put(
                    name,
                    sections.get(name)
            );
        }
    }

    private String joinBlocks(Map<String, String> sections) {
        StringBuilder content = new StringBuilder();
        for (String block : sections.values()) {
            separateBlock(content);
            content.append(block);
        }
        return content.toString();
    }

    private void separateBlock(StringBuilder content) {
        if (content.isEmpty()) {
            return;
        }
        char lastCharacter = content.charAt(content.length() - 1);
        if (lastCharacter == '\n' || lastCharacter == '\r') {
            return;
        }
        content.append("\n\n");
    }
}

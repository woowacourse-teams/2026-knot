package com.knot.backend.document.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class DocumentTranscriptTextReader {
    private final ObjectMapper mapper = new ObjectMapper();

    public String read(String content) {
        if (content == null || !content.stripLeading()
                .startsWith("{")) {
            return content;
        }
        try (JsonParser parser = mapper.createParser(content)) {
            JsonNode root = mapper.readTree(parser);
            if (parser.nextToken() != null || !isWhisperResult(root)) {
                return content;
            }
            return readUtterances(
                    root.path("transcription"),
                    content
            );
        } catch (JacksonException exception) {
            return content;
        }
    }

    private boolean isWhisperResult(JsonNode root) {
        if (root == null || !root.isObject() || !root.has("transcription")) {
            return false;
        }
        if (root.size() == 1 && root.path("transcription")
                .isArray()) {
            return true;
        }
        return root.path("model")
                .isObject()
                && root.path("params")
                        .isObject();
    }

    private String readUtterances(
            JsonNode transcription,
            String original
    ) {
        if (!transcription.isArray()) {
            return original;
        }
        List<String> utterances = new ArrayList<>();
        for (JsonNode segment : transcription) {
            JsonNode text = segment.path("text");
            if (!text.isString()) {
                return original;
            }
            utterances.add(text.asString());
        }
        return String.join(
                "\n",
                utterances
        );
    }
}

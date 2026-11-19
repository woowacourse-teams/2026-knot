package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.domain.SearchToolException;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
public class SearchAnswerJsonStream {

    private final ObjectMapper mapper;
    private final Consumer<String> onDelta;
    private final StringBuilder json = new StringBuilder();
    private final StringBuilder answer = new StringBuilder();
    private int offset = -1;
    private int sentLength;
    private boolean answerClosed;

    public void accept(String text) {
        json.append(text);
        locateAnswer();
        if (offset < 0 || answerClosed) {
            return;
        }
        decodeAnswer();
        emitAnswer();
    }

    public List<Long> complete(List<SearchDocumentResult> documents) {
        return complete(
                documents,
                0,
                false
        ).usedDocumentIds();
    }

    public SearchAnswerResponse complete(
            List<SearchDocumentResult> documents,
            int notionPageCount,
            boolean draftRequested
    ) {
        SearchAnswerResponse response = SearchAnswerResponse.parse(
                json.toString(),
                documents,
                notionPageCount,
                draftRequested,
                mapper
        );
        if (!answerClosed || !response.answer()
                .contentEquals(answer)) {
            throw new SearchToolException("INVALID_MODEL_ANSWER");
        }
        emitClarification(response);
        return response;
    }

    private void emitClarification(SearchAnswerResponse response) {
        String question = response.clarificationQuestion();
        if (question == null || response.answer()
                .contains(question)) {
            return;
        }
        if (!response.answer()
                .isEmpty()) {
            onDelta.accept("\n");
        }
        onDelta.accept(question);
    }

    private void locateAnswer() {
        if (offset >= 0) {
            return;
        }
        try (JsonParser parser = mapper.createParser(json.toString())) {
            int depth = 0;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                }
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
                if (depth == 1 && token == JsonToken.PROPERTY_NAME && "answer".equals(parser.currentName())) {
                    locateString(parser);
                    return;
                }
            }
        } catch (JacksonException exception) {
            // 필드가 분할된 경우 다음 delta를 기다리고 전체 계약은 complete에서 검증한다.
        }
    }

    private void locateString(JsonParser parser) {
        if (parser.nextToken() == JsonToken.VALUE_STRING) {
            offset = Math.toIntExact(
                    parser.currentTokenLocation()
                            .getCharOffset()
            ) + 1;
        }
    }

    private void decodeAnswer() {
        while (offset < json.length()) {
            if (!decodeCharacter()) {
                return;
            }
        }
    }

    private boolean decodeCharacter() {
        char value = json.charAt(offset);
        if (value == '"') {
            answerClosed = true;
            offset++;
            return false;
        }
        if (value == '\\') {
            return decodeEscape();
        }
        if (value < 0x20) {
            throw new SearchToolException("INVALID_MODEL_ANSWER");
        }
        answer.append(value);
        offset++;
        return true;
    }

    private boolean decodeEscape() {
        if (offset + 1 >= json.length()) {
            return false;
        }
        int length = 2;
        if (json.charAt(offset + 1) == 'u') {
            length = 6;
        }
        if (offset + length > json.length()) {
            return false;
        }
        try {
            answer.append(
                    mapper.readValue(
                            "\"" + json.substring(
                                    offset,
                                    offset + length
                            ) + "\"",
                            String.class
                    )
            );
            offset += length;
            return true;
        } catch (JacksonException exception) {
            throw new SearchToolException(
                    "INVALID_MODEL_ANSWER",
                    exception
            );
        }
    }

    private void emitAnswer() {
        int end = answer.length();
        if (end > 0 && Character.isHighSurrogate(answer.charAt(end - 1))) {
            end--;
        }
        if (end > sentLength) {
            onDelta.accept(
                    answer.substring(
                            sentLength,
                            end
                    )
            );
            sentLength = end;
        }
    }
}

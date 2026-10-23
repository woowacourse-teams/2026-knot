package com.knot.backend.global.infrastructure.llm;

import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import java.util.List;
import tools.jackson.databind.JsonNode;

final class LlmRequestValidator {

    void validate(LlmCompletionRequest request) {
        if (request == null) {
            throw invalidRequest();
        }
        List<LlmMessage> messages = request.messages();
        validateMessages(messages);
        String schemaName = request.schemaName();
        validateRequiredText(schemaName);
        JsonNode outputSchema = request.outputSchema();
        validateOutputSchema(outputSchema);
        LlmGenerationOptions options = request.options();
        validateOptions(options);
        if (request.timeout() != null && !request.timeout()
                .isPositive()) {
            throw invalidRequest();
        }
    }

    private void validateMessages(List<LlmMessage> messages) {
        if (messages == null) {
            throw invalidRequest();
        }
        if (messages.isEmpty()) {
            throw invalidRequest();
        }
        for (LlmMessage message : messages) {
            String role = message.role();
            validateRequiredText(role);
            if (message.content() == null) {
                throw invalidRequest();
            }
        }
    }

    private void validateRequiredText(String value) {
        if (value == null) {
            throw invalidRequest();
        }
        if (value.isBlank()) {
            throw invalidRequest();
        }
    }

    private void validateOutputSchema(JsonNode schema) {
        if (schema == null) {
            throw invalidRequest();
        }
        if (!schema.isObject()) {
            throw invalidRequest();
        }
    }

    private void validateOptions(LlmGenerationOptions options) {
        if (options == null) {
            throw invalidRequest();
        }
        if (options.maxTokens() <= 0) {
            throw invalidRequest();
        }
        if (options.topK() < 0) {
            throw invalidRequest();
        }
        double temperature = options.temperature();
        validateTemperature(temperature);
        double topP = options.topP();
        validateTopP(topP);
        double repeatPenalty = options.repeatPenalty();
        validateRepeatPenalty(repeatPenalty);
        Integer budget = options.thinkingBudgetTokens();
        validateThinkingBudget(budget);
    }

    private void validateTemperature(double value) {
        validateFinite(value);
        if (value < 0 || value > 2) {
            throw invalidRequest();
        }
    }

    private void validateTopP(double value) {
        validateFinite(value);
        if (value <= 0 || value > 1) {
            throw invalidRequest();
        }
    }

    private void validateRepeatPenalty(double value) {
        validateFinite(value);
        if (value <= 0) {
            throw invalidRequest();
        }
    }

    private void validateFinite(double value) {
        if (!Double.isFinite(value)) {
            throw invalidRequest();
        }
    }

    private void validateThinkingBudget(Integer budget) {
        if (budget == null) {
            return;
        }
        if (budget < 0) {
            throw invalidRequest();
        }
    }

    private LlmException invalidRequest() {
        return new LlmException(LlmErrorCode.LLM_INVALID_REQUEST);
    }
}

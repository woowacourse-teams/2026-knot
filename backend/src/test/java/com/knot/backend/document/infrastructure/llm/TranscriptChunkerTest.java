package com.knot.backend.document.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptChunkerTest {
    private final TranscriptChunker chunker = new TranscriptChunker();

    @Test
    void preservesEveryCharacterAndOrderAcrossLinesAndLongUtterance() {
        String input = "첫 번째 발언\n" + "긴 발언🙂".repeat(20) + "\n마지막 발언";
        List<String> chunks = chunker.split(
                input,
                17
        );
        assertThat(
                String.join(
                        "",
                        chunks
                )
        ).isEqualTo(input);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.length()).isLessThanOrEqualTo(17);
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
        });
    }

    @Test
    void rejectsBudgetsTooSmallForUnicode() {
        assertThatThrownBy(
                () -> chunker.split(
                        "🙂",
                        1
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefersBoundariesWithoutDroppingWhitespace() {
        assertThat(
                chunker.split(
                        "첫째\n둘째\n셋째",
                        5
                )
        ).containsExactly(
                "첫째\n",
                "둘째\n셋째"
        );
    }
}

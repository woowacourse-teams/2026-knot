package com.knot.backend.recording.domain.model;
import static org.assertj.core.api.Assertions.*;
import com.knot.backend.recording.domain.RecordingException;
import org.junit.jupiter.api.Test;
class SpeakerDisplayNameTest {
    @Test
    void unicodeNamesAndBoundary() {
        assertThat(new SpeakerDisplayName("　 루덴스 ").value()).isEqualTo("루덴스");
        assertThat(new SpeakerDisplayName("😀".repeat(50)).value()).hasSize(100);
        for (String invalid : new String[]{"　 ", "a".repeat(51), "루\n덴스", "루\t덴스", "루\u2028덴스"}) {
            assertThatThrownBy(() -> new SpeakerDisplayName(invalid)).isInstanceOf(RecordingException.class);
        }
    }
}

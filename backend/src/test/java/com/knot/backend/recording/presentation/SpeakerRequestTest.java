package com.knot.backend.recording.presentation;
import static org.assertj.core.api.Assertions.*;
import com.knot.backend.recording.presentation.dto.request.RecordingSpeakerCountRequest;
import com.knot.backend.recording.presentation.dto.request.RecordingSpeakerNameRequest;
import com.knot.backend.recording.domain.RecordingException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
class SpeakerRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test
    void countRequiresExplicitNullOrInteger() {
        assertThat(
                mapper.readValue(
                        "{\"speakerCount\":null}",
                        RecordingSpeakerCountRequest.class
                )
                        .count()
        ).isNull();
        for (String json : new String[]{"{}", "{\"speakerCount\":0}", "{\"speakerCount\":11}", "{\"speakerCount\":1.0}",
                "{\"speakerCount\":\"3\"}", "{\"speakerCount\":true}"}) {
            assertThatThrownBy(
                    () -> mapper.readValue(
                            json,
                            RecordingSpeakerCountRequest.class
                    )
                            .count()
            ).isInstanceOf(RecordingException.class);
        }
        assertThat(
                mapper.readValue(
                        "{\"speakerCount\":10}",
                        RecordingSpeakerCountRequest.class
                )
                        .count()
        ).isEqualTo(10);
    }
    @Test
    void nameCannotCoerceOtherJsonTypes() {
        for (String json : new String[]{"{}", "{\"displayName\":null}", "{\"displayName\":123}",
                "{\"displayName\":true}"}) {
            assertThatThrownBy(
                    () -> mapper.readValue(
                            json,
                            RecordingSpeakerNameRequest.class
                    )
                            .name()
            ).isInstanceOf(RecordingException.class);
        }
    }
}

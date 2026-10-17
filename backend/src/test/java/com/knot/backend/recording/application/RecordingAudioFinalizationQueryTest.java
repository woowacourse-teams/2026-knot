package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.result.AudioFrameRange;
import com.knot.backend.recording.domain.model.AudioFinalizationStatus;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import com.knot.backend.recording.domain.model.RecordingAudioFinalization;
import com.knot.backend.recording.domain.repository.RecordingAudioFinalizationRepository;
import com.knot.backend.recording.domain.repository.RecordingAudioStreamRepository;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecordingAudioFinalizationQueryTest {
    @Test
    void sourceQuality_mergesAdjacentMissingFramesAndKeepsWarningAfterDiscard() {
        // given
        RecordingAudioFinalizationRepository jobs = mock(RecordingAudioFinalizationRepository.class);
        RecordingAudioFinalization job = mock(RecordingAudioFinalization.class);
        ObjectMapper mapper = new ObjectMapper();
        List<AudioManifestPart> manifest = List.of(
                new AudioManifestPart(
                        1,
                        0,
                        80000,
                        "chunk1",
                        "hash",
                        null
                ),
                new AudioManifestPart(
                        2,
                        80000,
                        80000,
                        "chunk2",
                        "hash",
                        null
                ),
                new AudioManifestPart(
                        3,
                        160000,
                        1,
                        "chunk3",
                        "hash",
                        "v3"
                )
        );
        when(job.getStatus()).thenReturn(AudioFinalizationStatus.DISCARDED);
        when(job.getQuality()).thenReturn("PARTIAL");
        when(job.getReadyAt()).thenReturn(Instant.parse("2026-10-18T00:00:00Z"));
        when(job.getManifestJson()).thenReturn(mapper.writeValueAsString(manifest));
        when(jobs.findByRecordingId(1)).thenReturn(Optional.of(job));
        RecordingAudioFinalizationQuery query = new RecordingAudioFinalizationQuery(
                jobs,
                mock(RecordingSessionRepository.class),
                mapper,
                Clock.systemUTC(),
                mock(RecordingAudioStreamRepository.class)
        );

        // when
        com.knot.backend.recording.application.dto.result.RecordingSourceAudioQuality source = query.sourceQuality(1);

        // then
        assertThat(source.quality()).isEqualTo("PARTIAL");
        assertThat(source.missingRanges()).containsExactly(
                new AudioFrameRange(
                        0,
                        160000
                )
        );
    }
}

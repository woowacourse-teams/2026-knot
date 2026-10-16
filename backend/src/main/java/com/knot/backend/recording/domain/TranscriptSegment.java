package com.knot.backend.recording.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

@Getter
@Entity
@Table(name = "transcript_segments")
public class TranscriptSegment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transcript_id", nullable = false, updatable = false)
    private long transcriptId;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(name = "start_millis", nullable = false, updatable = false)
    private long startMillis;

    @Column(name = "end_millis", updatable = false)
    private Long endMillis;

    @Column(name = "speaker_number", updatable = false)
    private Integer speakerNumber;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String text;

    protected TranscriptSegment() {}

    private TranscriptSegment(
            long transcriptId,
            int position,
            long startMillis,
            Long endMillis,
            Integer speakerNumber,
            String text
    ) {
        validateTranscriptId(transcriptId);
        validatePosition(position);
        validateTimeRange(
                startMillis,
                endMillis
        );
        validateSpeakerNumber(speakerNumber);
        validateText(text);
        this.transcriptId = transcriptId;
        this.position = position;
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.speakerNumber = speakerNumber;
        this.text = text;
    }

    public static TranscriptSegment create(
            long transcriptId,
            int position,
            long startMillis,
            Long endMillis,
            Integer speakerNumber,
            String text
    ) {
        return new TranscriptSegment(
                transcriptId,
                position,
                startMillis,
                endMillis,
                speakerNumber,
                text
        );
    }

    public static List<TranscriptSegment> createAll(
            long transcriptId,
            List<TranscriptUtterance> utterances
    ) {
        if (utterances.isEmpty()) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        List<TranscriptSegment> segments = new ArrayList<>();
        Map<String, Integer> speakerNumbers = new HashMap<>();
        double previousStartSeconds = 0;
        for (int position = 0; position < utterances.size(); position++) {
            TranscriptUtterance utterance = utterances.get(position);
            if (utterance.startSeconds() < previousStartSeconds) {
                throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
            }
            segments.add(
                    create(
                            transcriptId,
                            position,
                            utterance.startMillis(),
                            utterance.endMillis(),
                            speakerNumber(
                                    speakerNumbers,
                                    utterance.speakerLabel()
                            ),
                            utterance.text()
                    )
            );
            previousStartSeconds = utterance.startSeconds();
        }
        return segments;
    }

    private static Integer speakerNumber(
            Map<String, Integer> speakerNumbers,
            String speakerLabel
    ) {
        if (speakerLabel == null || speakerLabel.isBlank()) {
            return null;
        }
        return speakerNumbers.computeIfAbsent(
                speakerLabel,
                ignored -> speakerNumbers.size() + 1
        );
    }

    private void validateTranscriptId(long transcriptId) {
        if (transcriptId <= 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
    }

    private void validatePosition(int position) {
        if (position < 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
    }

    private void validateTimeRange(
            long startMillis,
            Long endMillis
    ) {
        if (startMillis < 0) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (endMillis != null && endMillis < startMillis) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
        if (endMillis != null && endMillis > RecordingSession.MAX_RECORDING_MILLIS) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
    }

    private void validateSpeakerNumber(Integer speakerNumber) {
        if (speakerNumber != null && speakerNumber < 1) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
    }

    private void validateText(String text) {
        if (text == null || text.isBlank()) {
            throw new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
        }
    }
}

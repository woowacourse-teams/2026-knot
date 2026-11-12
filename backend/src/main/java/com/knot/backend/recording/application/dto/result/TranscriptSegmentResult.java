package com.knot.backend.recording.application.dto.result;

import com.knot.backend.recording.domain.model.TranscriptSegment;

public record TranscriptSegmentResult(
        long startMillis,
        Long endMillis,
        Integer speakerNumber,
        String text,
        String speakerDisplayName
) {

    public TranscriptSegmentResult(
            long startMillis,
            Long endMillis,
            Integer speakerNumber,
            String text
    ) {
        this(
                startMillis,
                endMillis,
                speakerNumber,
                text,
                null
        );
    }

    public TranscriptSegmentResult withDisplayName(String name) {
        return new TranscriptSegmentResult(
                startMillis,
                endMillis,
                speakerNumber,
                text,
                name
        );
    }

    public static TranscriptSegmentResult from(TranscriptSegment segment) {
        return new TranscriptSegmentResult(
                segment.getStartMillis(),
                segment.getEndMillis(),
                segment.getSpeakerNumber(),
                segment.getText(),
                null
        );
    }
}

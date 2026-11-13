package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.application.RecordingSpeakerNames;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RecordingSpeakerNamesAdapter implements RecordingSpeakerNames {
    private final JdbcClient jdbc;

    @Override
    public Map<Integer, String> findByTranscriptId(long transcriptId) {
        return jdbc.sql("""
                SELECT n.speaker_number, n.display_name FROM recording_speaker_names n
                JOIN transcripts t ON t.recording_session_id=n.recording_id WHERE t.id=:id
                """)
                .param(
                        "id",
                        transcriptId
                )
                .query(
                        (
                                row,
                                index
                        ) -> Map.entry(
                                row.getInt("speaker_number"),
                                row.getString("display_name")
                        )
                )
                .list()
                .stream()
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue
                        )
                );
    }

    @Override
    public void save(
            long recordingId,
            int speakerNumber,
            String displayName
    ) {
        jdbc.sql("""
                INSERT INTO recording_speaker_names(recording_id,speaker_number,display_name)
                VALUES (:id,:speaker,:name)
                ON CONFLICT (recording_id,speaker_number) DO UPDATE SET display_name=EXCLUDED.display_name
                """)
                .param(
                        "id",
                        recordingId
                )
                .param(
                        "speaker",
                        speakerNumber
                )
                .param(
                        "name",
                        displayName
                )
                .update();
    }
}

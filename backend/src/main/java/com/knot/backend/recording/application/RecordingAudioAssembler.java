package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.FinalizedAudioFile;
import com.knot.backend.recording.domain.model.AudioManifestPart;
import java.util.List;

public interface RecordingAudioAssembler {
    FinalizedAudioFile assemble(
            List<AudioManifestPart> parts,
            long totalFrames,
            Runnable checkpoint
    );
}

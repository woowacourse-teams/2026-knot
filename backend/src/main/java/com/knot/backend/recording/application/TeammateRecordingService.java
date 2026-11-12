package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.TeammateRecordingResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TeammateRecordingService {
    private final RecordingAccess recordingAccess;
    private final TeammateRecordingQuery teammateRecordingQuery;
    private final Clock clock;

    // 홈 녹음 기록의 팀원 줄(STT-R55)이다. 보기만 하므로 생존 신호, 누적 시간, 제어 권한을 바꾸지 않는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TeammateRecordingResult> findInProgress(
            long workspaceId,
            long memberId
    ) {
        recordingAccess.validateMember(
                workspaceId,
                memberId
        );
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);
        return teammateRecordingQuery.findInProgress(
                workspaceId,
                memberId
        )
                .stream()
                .map(
                        candidate -> TeammateRecordingResult.of(
                                candidate,
                                now
                        )
                )
                .toList();
    }
}

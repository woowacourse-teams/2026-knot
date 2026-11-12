package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.TeammateRecordingCandidate;
import java.util.List;

public interface TeammateRecordingQuery {

    // 같은 Workspace에서 요청자를 뺀 Member의 녹음 중·일시정지 녹음을 시작 시각, ID 내림차순으로 모두 찾는다.
    List<TeammateRecordingCandidate> findInProgress(
            long workspaceId,
            long excludedMemberId
    );
}

package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.application.dto.result.DocumentConfirmationSummaryResult;
import com.knot.backend.document.application.dto.result.DocumentDetailSnapshot;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentStatus;
import com.knot.backend.document.domain.model.MyConfirmationState;
import com.knot.backend.workspace.domain.WorkspaceException;
import com.knot.backend.workspace.domain.repository.WorkspaceMemberRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentDetailServiceTest {
    @Mock
    private WorkspaceMemberRepository workspaceMembers;
    @Mock
    private DocumentDetailQuery query;
    private DocumentDetailService service;

    @BeforeEach
    void setUp() {
        service = new DocumentDetailService(
                workspaceMembers,
                query,
                org.mockito.Mockito.mock(com.knot.backend.recording.application.RecordingAudioFinalizationQuery.class)
        );
    }

    @Test
    @DisplayName("현재 멤버에게 문서 상세와 내 확인 상태를 반환한다")
    void find_success() {
        // given
        when(
                workspaceMembers.existsByWorkspaceIdAndMemberId(
                        1L,
                        2L
                )
        ).thenReturn(true);
        when(
                query.find(
                        1,
                        3,
                        2
                )
        ).thenReturn(
                Optional.of(
                        new DocumentDetailSnapshot(
                                3,
                                4,
                                "주제",
                                "제목",
                                null,
                                "본문",
                                DocumentStatus.DRAFT,
                                Instant.parse("2026-10-06T00:00:00Z"),
                                null,
                                999,
                                5,
                                true,
                                null,
                                new DocumentConfirmationSummaryResult(
                                        0,
                                        1,
                                        0
                                )
                        )
                )
        );

        // when & then
        assertThat(
                service.find(
                        1,
                        2,
                        3
                )
                        .myConfirmationState()
        ).isEqualTo(MyConfirmationState.PENDING);
    }

    @Test
    @DisplayName("비멤버는 문서 존재 여부를 조회하지 않고 접근을 거절한다")
    void find_failure_accessDenied() {
        // when & then
        assertThatThrownBy(
                () -> service.find(
                        1,
                        2,
                        3
                )
        ).isInstanceOf(WorkspaceException.class)
                .extracting(
                        exception -> ((WorkspaceException) exception).getErrorCode()
                                .getCode()
                )
                .isEqualTo("WORKSPACE_ACCESS_DENIED");
        verifyNoInteractions(query);
    }

    @Test
    @DisplayName("조회 범위에 문서가 없으면 DOCUMENT_NOT_FOUND다")
    void find_failure_missingDocument() {
        // given
        when(
                workspaceMembers.existsByWorkspaceIdAndMemberId(
                        1L,
                        2L
                )
        ).thenReturn(true);
        when(
                query.find(
                        1,
                        3,
                        2
                )
        ).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(
                () -> service.find(
                        1,
                        2,
                        3
                )
        ).isInstanceOf(DocumentException.class)
                .extracting(exception -> ((DocumentException) exception).getErrorCode())
                .isEqualTo(DocumentErrorCode.DOCUMENT_NOT_FOUND);
    }
}

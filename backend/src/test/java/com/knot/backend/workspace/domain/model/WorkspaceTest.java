package com.knot.backend.workspace.domain.model;

import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-24T00:00:00Z");

    @DisplayName("유효한 이름으로 워크스페이스를 생성한다")
    @Test
    void create_success() {
        // given
        String name = "Knot 팀";

        // when
        Workspace workspace = Workspace.create(
                name,
                CREATED_AT
        );

        // then
        assertThat(workspace.getName()).isEqualTo(name);
        assertThat(workspace.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @DisplayName("워크스페이스 이름은 최대 20자까지 허용한다")
    @Test
    void create_success_maximumLengthName() {
        // given
        String maximumLengthName = "가".repeat(Workspace.MAX_NAME_LENGTH);

        // when
        Workspace workspace = Workspace.create(
                maximumLengthName,
                CREATED_AT
        );

        // then
        assertThat(workspace.getName()).isEqualTo(maximumLengthName);
    }

    @DisplayName("워크스페이스 이름에 숫자를 쓸 수 있고 숫자만으로도 만들 수 있다")
    @ParameterizedTest
    @ValueSource(strings = {"우테코 8기", "2026", "12345678901234567890"})
    void create_success_nameContainingDigits(String nameContainingDigits) {
        // when
        Workspace workspace = Workspace.create(
                nameContainingDigits,
                CREATED_AT
        );

        // then
        assertThat(workspace.getName()).isEqualTo(nameContainingDigits);
    }

    @DisplayName("워크스페이스 이름이 비어 있으면 생성을 거부한다")
    @Test
    void create_failure_blankName() {
        // given
        String blankName = " ";

        // when
        ThrowingCallable action = () -> Workspace.create(
                blankName,
                CREATED_AT
        );

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_NAME);
    }

    @DisplayName("워크스페이스 이름이 최대 길이를 넘으면 생성을 거부한다")
    @Test
    void create_failure_tooLongName() {
        // given
        String tooLongName = "가".repeat(Workspace.MAX_NAME_LENGTH + 1);

        // when
        ThrowingCallable action = () -> Workspace.create(
                tooLongName,
                CREATED_AT
        );

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_NAME);
    }

    @DisplayName("워크스페이스 이름에 특수문자가 있으면 생성을 거부한다")
    @Test
    void create_failure_specialCharacter() {
        // given
        String nameContainingSpecialCharacter = "Knot_팀";

        // when
        ThrowingCallable action = () -> Workspace.create(
                nameContainingSpecialCharacter,
                CREATED_AT
        );

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_NAME);
    }

    @DisplayName("워크스페이스 이름에 0~9가 아닌 숫자 문자가 있으면 생성을 거부한다")
    @ParameterizedTest
    @ValueSource(strings = {"２０２６", "①팀"})
    void create_failure_nonAsciiDigit(String nameContainingNonAsciiDigit) {
        // when
        ThrowingCallable action = () -> Workspace.create(
                nameContainingNonAsciiDigit,
                CREATED_AT
        );

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_NAME);
    }

    @DisplayName("생성 시각이 없으면 워크스페이스 생성을 거부한다")
    @Test
    void create_failure_missingCreatedAt() {
        // given
        Instant missingCreatedAt = null;

        // when
        ThrowingCallable action = () -> Workspace.create(
                "Knot 팀",
                missingCreatedAt
        );

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_CREATED_AT);
    }

    @DisplayName("삭제 시각을 기록해 워크스페이스를 논리 삭제한다")
    @Test
    void delete_success() {
        // given
        Workspace workspace = Workspace.create(
                "Knot 팀",
                CREATED_AT
        );
        Instant deletedAt = CREATED_AT.plusSeconds(1);

        // when
        workspace.delete(deletedAt);

        // then
        assertThat(workspace.isDeleted()).isTrue();
        assertThat(workspace.getDeletedAt()).isEqualTo(deletedAt);
    }

    @DisplayName("삭제 시각이 생성 시각보다 빠르면 논리 삭제를 거부한다")
    @Test
    void delete_failure_deletedAtBeforeCreatedAt() {
        // given
        Workspace workspace = Workspace.create(
                "Knot 팀",
                CREATED_AT
        );
        Instant deletedAtBeforeCreatedAt = CREATED_AT.minusSeconds(1);

        // when
        ThrowingCallable action = () -> workspace.delete(deletedAtBeforeCreatedAt);

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_DELETED_AT);
    }

    @DisplayName("다른 활성 멤버가 남는 탈퇴는 멤버만 탈퇴시키고 워크스페이스를 유지한다")
    @Test
    void removeMember_success_otherMembersRemain() {
        // given
        Workspace workspace = Workspace.create(
                "Knot 팀",
                CREATED_AT
        );
        WorkspaceMember member = WorkspaceMember.create(
                1L,
                2L,
                WorkspaceMemberRole.MEMBER,
                CREATED_AT
        );
        Instant leftAt = CREATED_AT.plusSeconds(1);

        // when
        workspace.removeMember(
                member,
                2L,
                leftAt
        );

        // then
        assertThat(member.getLeftAt()).isEqualTo(leftAt);
        assertThat(workspace.isDeleted()).isFalse();
    }

    @DisplayName("마지막 활성 멤버가 탈퇴하면 같은 시각으로 워크스페이스를 논리 삭제한다")
    @Test
    void removeMember_success_lastActiveMemberDeletesWorkspace() {
        // given
        Workspace workspace = Workspace.create(
                "Knot 팀",
                CREATED_AT
        );
        WorkspaceMember owner = WorkspaceMember.create(
                1L,
                2L,
                WorkspaceMemberRole.OWNER,
                CREATED_AT
        );
        Instant leftAt = CREATED_AT.plusSeconds(1);

        // when
        workspace.removeMember(
                owner,
                1L,
                leftAt
        );

        // then
        assertThat(owner.getLeftAt()).isEqualTo(leftAt);
        assertThat(workspace.getDeletedAt()).isEqualTo(leftAt);
    }

    @DisplayName("이미 탈퇴한 멤버는 마지막 멤버 수여도 워크스페이스를 삭제하지 않는다")
    @Test
    void removeMember_success_alreadyLeftMemberKeepsWorkspace() {
        // given
        Workspace workspace = Workspace.create(
                "Knot 팀",
                CREATED_AT
        );
        WorkspaceMember member = WorkspaceMember.create(
                1L,
                2L,
                WorkspaceMemberRole.MEMBER,
                CREATED_AT
        );
        Instant firstLeftAt = CREATED_AT.plusSeconds(1);
        member.leave(
                firstLeftAt,
                2L
        );

        // when
        workspace.removeMember(
                member,
                1L,
                CREATED_AT.plusSeconds(2)
        );

        // then
        assertThat(member.getLeftAt()).isEqualTo(firstLeftAt);
        assertThat(workspace.isDeleted()).isFalse();
    }

    @DisplayName("다른 활성 멤버가 있는 OWNER의 탈퇴는 승계 필요 오류로 거부하고 워크스페이스를 유지한다")
    @Test
    void removeMember_failure_ownerWithOtherActiveMember() {
        // given
        Workspace workspace = Workspace.create(
                "Knot 팀",
                CREATED_AT
        );
        WorkspaceMember owner = WorkspaceMember.create(
                1L,
                2L,
                WorkspaceMemberRole.OWNER,
                CREATED_AT
        );

        // when
        ThrowingCallable action = () -> workspace.removeMember(
                owner,
                2L,
                CREATED_AT.plusSeconds(1)
        );

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_OWNER_TRANSFER_REQUIRED);
        assertThat(owner.isActive()).isTrue();
        assertThat(workspace.isDeleted()).isFalse();
    }

    @DisplayName("저장된 워크스페이스는 생성자를 생성 시각에 참여한 OWNER 멤버십으로 만든다")
    @Test
    void createOwnerMembership_success() {
        // given
        Workspace workspace = Workspace.create(
                "Knot 팀",
                CREATED_AT
        );
        assignId(
                workspace,
                7L
        );

        // when
        WorkspaceMember owner = workspace.createOwnerMembership(3L);

        // then
        assertThat(owner.getWorkspaceId()).isEqualTo(7L);
        assertThat(owner.getMemberId()).isEqualTo(3L);
        assertThat(owner.getRole()).isEqualTo(WorkspaceMemberRole.OWNER);
        assertThat(owner.getJoinedAt()).isEqualTo(CREATED_AT);
    }

    @DisplayName("저장되지 않아 ID가 없는 워크스페이스는 OWNER 멤버십을 만들 수 없다")
    @Test
    void createOwnerMembership_failure_unsavedWorkspace() {
        // given
        Workspace workspace = Workspace.create(
                "Knot 팀",
                CREATED_AT
        );

        // when
        ThrowingCallable action = () -> workspace.createOwnerMembership(3L);

        // then
        assertThatThrownBy(action).isInstanceOf(WorkspaceException.class)
                .extracting(exception -> ((WorkspaceException) exception).getErrorCode())
                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_ID);
    }

    private void assignId(
            Workspace workspace,
            long id
    ) {
        try {
            Field field = Workspace.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(
                    workspace,
                    id
            );
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new IllegalStateException(
                    "저장된 워크스페이스 fixture를 만들 수 없습니다",
                    exception
            );
        }
    }
}

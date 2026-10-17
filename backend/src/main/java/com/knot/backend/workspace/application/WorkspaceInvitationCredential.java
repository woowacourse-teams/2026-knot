package com.knot.backend.workspace.application;

import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.util.Locale;
import java.util.regex.Pattern;

public record WorkspaceInvitationCredential(
        WorkspaceInvitationSecretKind kind,
        String secret
) {
    static final int CODE_LENGTH = 6;
    private static final Pattern INVITE_CODE_PATTERN = Pattern.compile("[A-Z2-9]{" + CODE_LENGTH + "}");

    public static WorkspaceInvitationCredential from(String tokenOrCode) {
        if (tokenOrCode == null || tokenOrCode.isBlank()) {
            throw previewNotFound();
        }
        String stripped = tokenOrCode.strip();
        if (stripped.length() == CODE_LENGTH) {
            return inviteCode(stripped);
        }
        return new WorkspaceInvitationCredential(
                WorkspaceInvitationSecretKind.LINK_TOKEN,
                tokenOrCode
        );
    }

    private static WorkspaceInvitationCredential inviteCode(String stripped) {
        String normalizedCode = stripped.toUpperCase(Locale.ROOT);
        return new WorkspaceInvitationCredential(
                WorkspaceInvitationSecretKind.INVITE_CODE,
                normalizedCode
        );
    }

    public boolean requiresRateLimiting() {
        return kind == WorkspaceInvitationSecretKind.INVITE_CODE;
    }

    public void validate() {
        if (kind != WorkspaceInvitationSecretKind.INVITE_CODE) {
            return;
        }
        if (!INVITE_CODE_PATTERN.matcher(secret)
                .matches()) {
            throw previewNotFound();
        }
    }

    private static WorkspaceException previewNotFound() {
        return new WorkspaceException(WorkspaceErrorCode.WORKSPACE_INVITATION_PREVIEW_NOT_FOUND);
    }
}

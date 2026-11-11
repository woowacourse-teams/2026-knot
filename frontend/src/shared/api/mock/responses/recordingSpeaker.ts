import type { WorkspaceMemberPage } from "@api/mock/types/recordingSpeaker";
export const workspaceMembersResponse = {
  items: [
    {
      memberId: 1,
      nickname: "루덴스",
      profileImageUrl: null,
      role: "OWNER",
      joinedAt: "2026-10-20T00:00:00Z",
    },
    {
      memberId: 2,
      nickname: "흑곰",
      profileImageUrl: null,
      role: "MEMBER",
      joinedAt: "2026-10-20T00:00:01Z",
    },
    {
      memberId: 3,
      nickname: "이스타",
      profileImageUrl: null,
      role: "MEMBER",
      joinedAt: "2026-10-20T00:00:02Z",
    },
    {
      memberId: 4,
      nickname: "도넛",
      profileImageUrl: null,
      role: "MEMBER",
      joinedAt: "2026-10-20T00:00:03Z",
    },
  ],
  nextCursor: null,
} satisfies WorkspaceMemberPage;

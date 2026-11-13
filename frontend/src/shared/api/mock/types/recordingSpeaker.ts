export interface SpeakerCountRequest {
  tabId: string;
  controlToken: string;
  speakerCount: number | null;
}
export interface SpeakerNameRequest {
  displayName: string;
}
export interface WorkspaceMemberPage {
  items: {
    memberId: number;
    nickname: string;
    profileImageUrl: string | null;
    role: "OWNER" | "MEMBER";
    joinedAt: string;
  }[];
  nextCursor: string | null;
}

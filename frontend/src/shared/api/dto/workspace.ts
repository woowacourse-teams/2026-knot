/**
 * 워크스페이스 DTO
 *
 * - GET  /api/v1/workspaces
 * - POST /api/v1/workspaces
 * - GET  /api/v1/workspaces/{workspaceId}
 * - GET  /api/v1/workspaces/{workspaceId}/members
 * - PUT  /api/v1/members/me/last-viewed-workspace
 * - DELETE /api/v1/workspaces/{workspaceId}/members/me (요청과 응답 본문 없음)
 * - GET  /api/v1/workspaces/{workspaceId}/ownership-transfer-candidates
 * - POST /api/v1/workspaces/{workspaceId}/ownership-transfers
 */

/** 워크스페이스 목록 항목의 서버 응답 모양 */
export interface WorkspaceListItemRaw {
  id: number;
  name: string;
}

/** 워크스페이스 목록 항목. 목록 조회 응답의 `workspaces`에 들어감 */
export class WorkspaceListItemDto {
  /** 워크스페이스 ID */
  id: number;
  /** 워크스페이스 이름. 최대 20자 */
  name: string;

  constructor(raw: WorkspaceListItemRaw) {
    this.id = raw.id;
    this.name = raw.name;
  }
}

// GET /api/v1/workspaces

/** 워크스페이스 목록 조회의 서버 응답 모양 */
export interface GetWorkspacesResponseRaw {
  lastViewedWorkspaceId: number | null;
  workspaces: WorkspaceListItemRaw[];
}

/** 내가 속한 워크스페이스 목록 조회 응답 */
export class GetWorkspacesResponseDto {
  /** 마지막으로 본 워크스페이스 ID. 본 적이 없으면 null */
  lastViewedWorkspaceId: number | null;
  /** 내가 속한 워크스페이스 목록. 없으면 빈 배열 */
  workspaces: WorkspaceListItemDto[];

  constructor(raw: GetWorkspacesResponseRaw) {
    this.lastViewedWorkspaceId = raw.lastViewedWorkspaceId;
    this.workspaces = raw.workspaces.map(
      (item) => new WorkspaceListItemDto(item),
    );
  }
}

// POST /api/v1/workspaces

/** 워크스페이스 생성 시 앱이 넘기는 값 */
export interface PostWorkspaceRequestInput {
  name: string;
}

/** 워크스페이스 생성 요청 본문 */
export class PostWorkspaceRequestDto {
  /** 워크스페이스 이름. 앞뒤 공백 제거. 한글·영문·공백만, 최대 20자, 한글이나 영문을 하나 이상 포함 */
  name: string;

  constructor({ name }: PostWorkspaceRequestInput) {
    this.name = name.trim();
  }
}

/** 워크스페이스 생성의 서버 응답 모양 */
export interface PostWorkspaceResponseRaw {
  id: number;
}

/** 워크스페이스 생성 응답 */
export class PostWorkspaceResponseDto {
  /** 생성된 워크스페이스 ID */
  id: number;

  constructor(raw: PostWorkspaceResponseRaw) {
    this.id = raw.id;
  }
}

// GET /api/v1/workspaces/{workspaceId}

/** 워크스페이스 단건 조회의 서버 응답 모양 */
export interface GetWorkspaceResponseRaw {
  name: string;
  myRole: "OWNER" | "MEMBER";
  activeMemberCount: number;
}

/** 워크스페이스 단건 조회 응답 */
export class GetWorkspaceResponseDto {
  /** 워크스페이스 이름. 최대 20자 */
  name: string;
  /** 이 워크스페이스에서 내가 맡은 역할 */
  myRole: "OWNER" | "MEMBER";
  /** 나를 포함해 탈퇴하지 않은 멤버 수. 1이면 나 혼자 */
  activeMemberCount: number;

  constructor(raw: GetWorkspaceResponseRaw) {
    this.name = raw.name;
    this.myRole = raw.myRole;
    this.activeMemberCount = raw.activeMemberCount;
  }
}

// PUT /api/v1/members/me/last-viewed-workspace

/** 마지막으로 본 워크스페이스 갱신 시 앱이 넘기는 값 */
export interface PutLastViewedWorkspaceRequestInput {
  workspaceId: number;
}

/** 마지막으로 본 워크스페이스 갱신 요청 본문. 성공 시 응답 본문 없음(204) */
export class PutLastViewedWorkspaceRequestDto {
  /** 마지막으로 본 워크스페이스 ID */
  workspaceId: number;

  constructor({ workspaceId }: PutLastViewedWorkspaceRequestInput) {
    this.workspaceId = workspaceId;
  }
}

// GET /api/v1/workspaces/{workspaceId}/ownership-transfer-candidates

/** OWNER 승계 후보의 서버 응답 모양 */
export interface OwnershipTransferCandidateRaw {
  memberId: number;
  nickname: string;
}

/** OWNER 승계 후보. 승계 후보 조회 응답의 `candidates`에 들어감 */
export class OwnershipTransferCandidateDto {
  /** 후보 멤버의 ID. 승계 요청의 `successorMemberId`로 넘김 */
  memberId: number;
  /** 후보 멤버의 닉네임 */
  nickname: string;

  constructor(raw: OwnershipTransferCandidateRaw) {
    this.memberId = raw.memberId;
    this.nickname = raw.nickname;
  }
}

/** OWNER 승계 후보 조회의 서버 응답 모양. 본문이 배열 하나 */
export type GetOwnershipTransferCandidatesResponseRaw =
  OwnershipTransferCandidateRaw[];

/** OWNER 승계 후보 조회 응답. OWNER만 볼 수 있고(그 외 403), 서버 배열을 `candidates` 필드에 담음 */
export class GetOwnershipTransferCandidatesResponseDto {
  /** 나와 탈퇴한 멤버를 뺀 활성 멤버 목록. memberId 오름차순이고 없으면 빈 배열 */
  candidates: OwnershipTransferCandidateDto[];

  constructor(raw: GetOwnershipTransferCandidatesResponseRaw) {
    this.candidates = raw.map(
      (candidate) => new OwnershipTransferCandidateDto(candidate),
    );
  }
}

// POST /api/v1/workspaces/{workspaceId}/ownership-transfers

/** OWNER 승계 시 앱이 넘기는 값 */
export interface PostOwnershipTransferRequestInput {
  successorMemberId: number;
}

/** OWNER 승계와 탈퇴 요청 본문. 성공 시 응답 본문 없음(204)이고 탈퇴까지 함께 처리됨 */
export class PostOwnershipTransferRequestDto {
  /** OWNER를 넘겨받을 멤버의 ID. 승계 후보의 `memberId` */
  successorMemberId: number;

  constructor({ successorMemberId }: PostOwnershipTransferRequestInput) {
    this.successorMemberId = successorMemberId;
  }
}

export interface WorkspaceMemberRaw {
  memberId: number;
  nickname: string;
  profileImageUrl: string | null;
  role: "OWNER" | "MEMBER";
  joinedAt: string;
}
export interface GetWorkspaceMembersResponseRaw {
  items: WorkspaceMemberRaw[];
  nextCursor: string | null;
}
/** 이름 선택에 쓰는 현재 워크스페이스 멤버. */
export class WorkspaceMemberDto {
  /** 계정 식별자. 이름 변경 요청에는 보내지 않아요. */
  memberId: number;
  /** 선택 시 표시 이름으로 복사할 닉네임 */
  nickname: string;
  /** 프로필 사진 URL. 없으면 null */
  profileImageUrl: string | null;
  /** 현재 워크스페이스 역할 */
  role: "OWNER" | "MEMBER";
  /** 가입 시각 ISO 8601 */
  joinedAt: string;
  constructor(raw: WorkspaceMemberRaw) {
    this.memberId = raw.memberId;
    this.nickname = raw.nickname;
    this.profileImageUrl = raw.profileImageUrl;
    this.role = raw.role;
    this.joinedAt = raw.joinedAt;
  }
}
/** 현재 멤버 목록의 한 페이지. */
export class GetWorkspaceMembersResponseDto {
  /** 가입 시각·회원 ID 오름차순의 활성 멤버 */
  items: WorkspaceMemberDto[];
  /** 다음 페이지가 없으면 null */
  nextCursor: string | null;
  constructor(raw: GetWorkspaceMembersResponseRaw) {
    this.items = raw.items.map((member) => new WorkspaceMemberDto(member));
    this.nextCursor = raw.nextCursor;
  }
}

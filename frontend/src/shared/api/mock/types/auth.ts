export interface MeResponse {
  memberId: number;
  nickname: string;
  profileImageUrl: string | null;
}

export interface CsrfTokenResponse {
  token: string;
}

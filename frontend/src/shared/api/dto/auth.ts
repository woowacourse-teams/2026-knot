/**
 * 인증·로그인 회원 DTO
 *
 * - GET  /api/v1/auth/csrf
 * - GET  /api/v1/auth/me
 * - POST /api/v1/auth/nickname
 */

// GET /api/v1/auth/csrf

/** CSRF 토큰 조회의 서버 응답 모양. swagger의 `csrfToken` 쿼리 파라미터는 서버가 주입하므로 클라이언트가 보내지 않음 */
export interface GetCsrfTokenResponseRaw {
  token: string;
}

/** CSRF 토큰 조회 응답 */
export class GetCsrfTokenResponseDto {
  /** 변경 요청(POST·PUT)의 `X-XSRF-TOKEN` 헤더에 넣을 토큰 */
  token: string;

  constructor(raw: GetCsrfTokenResponseRaw) {
    this.token = raw.token;
  }
}

// GET /api/v1/auth/me

/** 로그인한 회원 정보 조회의 서버 응답 모양 */
export interface GetMeResponseRaw {
  memberId: number;
  nickname: string;
  profileImageUrl: string | null;
}

/** 로그인한 회원 정보 조회 응답 */
export class GetMeResponseDto {
  /** 로그인한 회원의 ID */
  memberId: number;
  /** 회원 닉네임. 최대 20자 */
  nickname: string;
  /** 프로필 이미지의 절대 URL. 로그인 수단에 사진이 없거나 카카오에서 사진 제공에 동의하지 않았으면 null */
  profileImageUrl: string | null;

  constructor(raw: GetMeResponseRaw) {
    this.memberId = raw.memberId;
    this.nickname = raw.nickname;
    this.profileImageUrl = raw.profileImageUrl;
  }
}

// POST /api/v1/auth/nickname

/** 닉네임 설정 시 앱이 넘기는 값 */
export interface PostNicknameRequestInput {
  nickname: string;
}

/** 첫 로그인 뒤 닉네임을 정해 가입을 마치는 요청 본문. 성공 시 응답 본문 없음 */
export class PostNicknameRequestDto {
  /** 회원 닉네임. 최대 20자 */
  nickname: string;

  constructor({ nickname }: PostNicknameRequestInput) {
    this.nickname = nickname;
  }
}

/**
 * 이 탭의 로그인 상태 기록. 로그인 만료 토스트(ERR-07)를 처음 온 비로그인 사용자와 나눠 띄우려고 둬요.
 *
 * 인증 쿠키는 `httpOnly`라 읽을 수 없고, 서버는 refresh 토큰이 없을 때와 만료됐을 때 같은 401을 줘요.
 * 그래서 이 탭에서 로그인을 확인한 적이 있는지를 따로 기억해, 그 뒤의 재발급 실패만 만료로 봐요.
 * 새로고침해도 같은 탭이면 남도록 sessionStorage에 둬요. 다른 탭이나 새 창에서 처음 열면 기록이 없어 알리지 않아요.
 */
const AUTH_SESSION_KEY = "knot.auth.session";

type AuthSessionState = "confirmed" | "expired";

// 비공개 창·저장소 차단처럼 sessionStorage를 못 쓰면 예외를 삼켜 토스트 없이 로그인 화면으로 보내는 기존 동작을 유지해요

const readAuthSession = () => {
  try {
    return sessionStorage.getItem(AUTH_SESSION_KEY) as AuthSessionState | null;
  } catch {
    return null;
  }
};

const writeAuthSession = (state: AuthSessionState | null) => {
  try {
    if (state === null) {
      sessionStorage.removeItem(AUTH_SESSION_KEY);
      return;
    }

    sessionStorage.setItem(AUTH_SESSION_KEY, state);
  } catch {
    // 기록하지 못하면 만료를 알리지 않을 뿐이에요
  }
};

/** 로그인 확인이 성공해 이 탭이 로그인한 상태라고 기록해요 */
export const markAuthSessionConfirmed = () => {
  writeAuthSession("confirmed");
};

/** 토큰 재발급이 거절됐을 때 불러요. 이 탭에서 로그인을 확인한 적이 있을 때만 만료로 기록해요 */
export const markAuthSessionExpired = () => {
  if (readAuthSession() !== "confirmed") return;

  writeAuthSession("expired");
};

/** 만료 기록이 있으면 지우고 `true`를 돌려줘요. 로그인 화면이 만료를 한 번만 알리려고 불러요 */
export const consumeAuthSessionExpired = () => {
  if (readAuthSession() !== "expired") return false;

  writeAuthSession(null);

  return true;
};

/** 로그아웃에 성공하면 불러 기록을 지워요. 뒤따르는 재발급 거절이 로그인 만료로 남지 않아요(AUTH-R21) */
export const clearAuthSession = () => {
  writeAuthSession(null);
};

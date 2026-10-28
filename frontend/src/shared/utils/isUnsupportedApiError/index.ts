import { HTTP_ERROR_TYPE, isHttpError } from "@api/httpClient/error";

/**
 * 서버에 요청 경로가 없다는 응답인지 확인해요. 자원 미존재나 권한 오류는 포함하지 않아요.
 * 지원 여부를 확인하는 신규 API의 조회 결과에만 사용하며, 배포 지연의 원인을 판정하지 않아요.
 */
export const isUnsupportedApiError = (error: unknown) =>
  isHttpError(error, HTTP_ERROR_TYPE.notFound) &&
  error.code === "REQUEST_PATH_NOT_FOUND";

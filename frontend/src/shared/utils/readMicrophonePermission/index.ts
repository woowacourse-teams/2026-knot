/**
 * @description 브라우저가 알고 있는 마이크 권한 상태(`prompt` · `granted` · `denied`)를 읽어요
 * @example
 * const permission = await readMicrophonePermission();
 *
 * 권한 창을 띄우지 않고 읽기만 해요. 권한 조회가 없거나 마이크 권한을 조회할 수 없는 브라우저면 `unsupported`예요.
 */
export const readMicrophonePermission = async () => {
  try {
    const { state } = await navigator.permissions.query({ name: "microphone" });

    return state;
  } catch {
    return "unsupported" as const;
  }
};

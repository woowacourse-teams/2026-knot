/** API와 동일하게 Unicode 코드 포인트를 세고 제어문자·개행을 거절해요. */
export const validateSpeakerName = (value: string) => {
  if (/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/u.test(value))
    return "이름에 줄바꿈이나 제어 문자를 넣을 수 없어요.";
  const length = [...value.trim()].length;
  return length >= 1 && length <= 50 ? null : "이름은 1~50자로 입력해 주세요.";
};

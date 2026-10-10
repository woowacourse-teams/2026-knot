/**
 * 녹음 화면 카드의 제목을 녹음을 시작한 사람의 닉네임으로 만듭니다.
 *
 * 문서 제목은 녹음이 끝난 뒤 주제별로 붙으므로, 녹음 중에는 누가 시작한 녹음인지만 보여 줍니다.
 *
 * @param nickname - 녹음을 시작한 사람의 닉네임. 아직 불러오지 못했으면 비어 있음
 * @returns `"{닉네임} 님의 녹음"`. 닉네임이 없으면 `"녹음"`
 *
 * @example
 * formatRecordingTitle("노티드");
 * // "노티드 님의 녹음"
 */
export const formatRecordingTitle = (nickname?: string) => {
  return nickname ? `${nickname} 님의 녹음` : "녹음";
};

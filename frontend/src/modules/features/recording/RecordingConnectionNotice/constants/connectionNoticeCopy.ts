/** 녹음 화면의 연결 끊김 줄 문구. 시안 `Recorder/ConnectionNotice`와 같아요 */
export const CONNECTION_NOTICE_COPY = {
  lost: {
    title: "연결이 끊겼어요. 녹음은 계속돼요",
    description: "연결이 돌아오면 끊긴 동안의 소리도 이어서 올려요",
  },
  archiveFailed: {
    title: "연결이 끊겼어요. 녹음은 계속돼요",
    description:
      "보관할 공간이 부족해요. 탭을 닫으면 끊긴 동안의 소리가 사라질 수 있어요",
  },
  ending: {
    title: "연결이 돌아오면 녹음을 끝낼게요",
    description: "남은 소리를 먼저 올린 뒤 끝내요. 이 탭을 닫지 말아 주세요",
  },
} as const;

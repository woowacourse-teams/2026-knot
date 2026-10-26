import useMeQuery from "@api/queries/useMeQuery";

/**
 * 녹음 이름. 현재 녹음 조회는 내 녹음만 돌려주므로 로그인한 내 닉네임으로 만들어요.
 *
 * 닉네임을 불러오는 동안에는 `isLoading`이 `true`예요.
 */
export const useRecordingTitle = () => {
  const { data: me, isLoading, isError } = useMeQuery();

  const title =
    isError || !me?.nickname ? "진행 중인 녹음" : `${me.nickname} 님의 녹음`;

  return { title, isLoading };
};

import { useEffect, useRef, useState } from "react";

interface UseLoadMoreOnReachEndParams {
  /** 목록 끝에 닿았을 때 다음 페이지를 받을지. 다음 페이지가 없거나, 받는 중이거나, 받기에 실패했으면 꺼요 */
  isEnabled: boolean;
  /** 목록 끝에 닿았을 때 */
  onReachEnd: () => void;
}

/**
 * 목록 끝의 표시 요소가 화면에 들어오면 다음 페이지를 받게 하는 훅. 돌려준 ref를 목록 맨 아래 요소에 달아요.
 *
 * 화면 안에 들어왔는지는 IntersectionObserver로 봐요. 창이 아니라 레이아웃 본문 안에서 스크롤돼도, 요소가 창에 보이는지로 판단하므로 그대로 동작해요.
 * 받는 중이거나 실패한 동안에는 꺼 두어, 같은 페이지를 거듭 요청하거나 실패한 요청을 저절로 되풀이하지 않아요. 실패 뒤에는 사람이 「다시 시도」를 눌러요.
 * 목록이 켜진 뒤에 늦게 그려져도(다른 조회를 기다리는 동안 뼈대를 보여 준 경우) 표시 요소가 붙는 순간 관찰을 시작해요.
 */
const useLoadMoreOnReachEnd = ({
  isEnabled,
  onReachEnd,
}: UseLoadMoreOnReachEndParams) => {
  // 요소가 붙고 떨어질 때 관찰을 다시 걸도록 ref 대신 상태로 받아요
  const [sentinel, sentinelRef] = useState<HTMLDivElement | null>(null);
  // 가장 최근 핸들러를 쓰되, 핸들러가 바뀔 때마다 관찰을 새로 걸지 않아요
  const onReachEndRef = useRef(onReachEnd);

  useEffect(() => {
    onReachEndRef.current = onReachEnd;
  });

  useEffect(() => {
    if (!isEnabled || sentinel === null) return;

    const observer = new IntersectionObserver((entries) => {
      if (entries.some(({ isIntersecting }) => isIntersecting)) {
        onReachEndRef.current();
      }
    });
    observer.observe(sentinel);

    return () => {
      observer.disconnect();
    };
  }, [isEnabled, sentinel]);

  return { sentinelRef };
};

export default useLoadMoreOnReachEnd;

import VisuallyHidden from "@primitives/ui/VisuallyHidden";
import { useEffect, useRef, useState } from "react";
import { useLocation } from "react-router";

/**
 * 화면을 옮기면 새 화면의 제목(`<title>`)을 낭독기가 읽게 하는 안내 영역.
 *
 * 화면을 옮겨도 페이지를 새로 불러오지 않으므로 낭독기는 새 제목을 스스로 읽지 않아요. 그래서 경로가 바뀌면 제목을 이 상태 영역에 적어요.
 * 커서는 옮기지 않아요. 워크스페이스 독은 한글 입력 때문에 커서를 입력창에 붙잡아 두는데(`useKeepFieldFocused`), 커서를 본문으로 옮기면 그 동작과 부딪혀요.
 *
 * - 처음 들어온 화면은 낭독기가 페이지 제목을 스스로 읽으므로 적지 않아요.
 * - 쿼리만 바뀌면(패널 여닫기) 같은 화면이라 적지 않아요.
 * - 새 화면이 불러오기를 마친 뒤에 제목을 그리면(워크스페이스 입장 확인) 그려진 제목을 따라 다시 적어요.
 */
export default function RouteAnnouncer() {
  const { pathname } = useLocation();
  const [message, setMessage] = useState("");
  // 처음 그릴 때의 경로로 시작해, 개발 모드(StrictMode)가 효과를 한 번 더 돌려도 처음 화면을 적지 않아요
  const previousPathnameRef = useRef(pathname);

  useEffect(() => {
    if (previousPathnameRef.current === pathname) return;
    previousPathnameRef.current = pathname;

    const announceTitle = () => setMessage(document.title);
    announceTitle();

    // 같은 글자로 바뀌면 React가 다시 그리지 않아, head에 스타일이 붙을 때마다 불려도 다시 읽지 않아요
    const observer = new MutationObserver(announceTitle);
    observer.observe(document.head, {
      childList: true,
      subtree: true,
      characterData: true,
    });

    return () => observer.disconnect();
  }, [pathname]);

  return <VisuallyHidden role="status">{message}</VisuallyHidden>;
}

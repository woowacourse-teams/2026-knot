import { useState, type ReactNode } from "react";

import type { ReleaseNote } from "@/shared/types/releaseNote";

import type { NewsPopoverView } from "../types/newsPopoverView";

import NewsMenu from "./NewsMenu";
import NewsPanel from "./NewsPanel";

interface NewsPopoverContentProps {
  /** 열렸을 때 처음 보여 줄 것 */
  initialView: NewsPopoverView;
  /** 새 소식 패널에 보여 줄 노트 */
  notes: ReleaseNote[];
  /** 안 읽은 노트의 id. 있으면 메뉴의 「새 소식」과 새 소식 패널의 그 노트에 NEW 칩을 붙여요 */
  unreadNoteIds: string[];
  /** 메뉴에서 「새 소식」을 골라 새 소식 패널을 보여 줄 때 불러요 */
  onNewsOpen: () => void;
  /** 메뉴에서 「피드백 보내기」를 고르면 그 자리에 그릴 피드백 패널 */
  feedbackPanel: ReactNode;
}

/**
 * 「소식과 피드백」 팝오버의 내용. 메뉴, 새 소식 패널, 피드백 패널 중 하나를 그려요.
 *
 * 무엇을 보여 줄지는 팝오버가 열린 동안만 들고 있어요. 닫히면 내용이 사라지므로 다음에 열 때는 `initialView`부터 보여요.
 */
export default function NewsPopoverContent({
  initialView,
  notes,
  unreadNoteIds,
  onNewsOpen,
  feedbackPanel,
}: NewsPopoverContentProps) {
  const [view, setView] = useState(initialView);

  if (view === "news") return <NewsPanel notes={notes} />;
  if (view === "feedback") return feedbackPanel;

  return (
    <NewsMenu
      hasUnreadNews={unreadNoteIds.length > 0}
      onNewsSelect={() => {
        setView("news");
        onNewsOpen();
      }}
      onFeedbackSelect={() => setView("feedback")}
    />
  );
}

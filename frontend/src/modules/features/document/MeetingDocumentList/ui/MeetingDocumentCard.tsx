import RelatedCard from "@primitives/ui/RelatedCard";

import FileIcon from "@/assets/icons/file.svg";

interface MeetingDocumentCardProps {
  /** 카드를 누르면 갈 문서 보기 주소 */
  href: string;
  title: string;
  /** 한 줄 요약. 없으면 null이고, 그때는 제목만 보여줘요 */
  summary: string | null;
}

/**
 * 「이 회의에서 나온 문서」의 문서 카드 하나. 제목과 한 줄 요약을 담고, 누르면 그 문서의 문서 보기로 가요(STT-R52). 피그마 Card/RelatedDoc 상태=기본이에요.
 *
 * 주제 · 날짜 · 녹음 길이 · 확인 상태는 보여주지 않아요. 제목과 요약은 한 줄을 넘으면 말줄임해요.
 */
export default function MeetingDocumentCard({
  href,
  title,
  summary,
}: MeetingDocumentCardProps) {
  return (
    <li>
      <RelatedCard
        href={href}
        icon={<FileIcon size={16} aria-hidden />}
        title={title}
        description={summary}
      />
    </li>
  );
}

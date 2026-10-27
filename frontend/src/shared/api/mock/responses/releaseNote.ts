import type { ReleaseNote, ReleaseNoteItem } from "@api/mock/types/releaseNote";

const GITHUB_IMAGE_URL = "https://github.com/user-attachments/assets";

/**
 * 사진 칸 경우별 항목(NEWS-R13, R14). 경우는 Figma 사진 칸 경우별 모양(node 5674-70369)과 같아요.
 * github.com 사진은 가짜 사진 응답(`handlers/relay/n`)이 주소에 적힌 크기로 그려 주고, 그 밖의 주소는 404예요
 */
export const imageReleaseNoteItems = {
  normal: {
    title: "보통 비율 사진이 있는 항목이에요",
    description:
      "1596×1386 사진이에요. 폭에 맞추면 240보다 높아 높이 240으로 줄이고 가운데에 둬요.",
    image: `${GITHUB_IMAGE_URL}/news-1596x1386`,
  },
  wide: {
    title: "아주 납작한 사진이 있는 항목이에요",
    description: "2560×420 사진이에요. 폭에 맞춘 높이 그대로 보여요.",
    image: `${GITHUB_IMAGE_URL}/news-2560x420`,
  },
  tall: {
    title: "세로로 긴 사진이 있는 항목이에요",
    description:
      "750×1624 사진이에요. 높이 240으로 줄이고 남는 양옆은 사진 칸 배경색이에요.",
    image: `${GITHUB_IMAGE_URL}/news-750x1624`,
  },
  broken: {
    title: "열리지 않는 사진이 있는 항목이에요",
    description: "사진을 불러오지 못해 사진 칸 없이 글만 보여요.",
    image: `${GITHUB_IMAGE_URL}/news-broken`,
  },
  disallowed: {
    title: "허용하지 않는 사진 주소가 있는 항목이에요",
    description:
      "GitHub 첨부 사진 주소가 아니라 사진을 불러오지 않고 글만 보여요.",
    image:
      "https://github-production-user-asset-6210df.s3.amazonaws.com/news-s3.png",
  },
} satisfies Record<string, ReleaseNoteItem>;

// 새 소식 DB에 처음 옮겨 넣은 v0.2.0과 v0.1.0 노트예요. 문구는 Figma 패널 목록(node 4696-29495) 그대로예요.
// 맨 끝 노트는 사진 칸 경우를 로컬과 E2E에서 보려고 더했어요. 가장 오래된 노트로 둬 맨 위 노트 기준의 읽음 표시에 닿지 않아요
export const releaseNotesResponse = [
  {
    id: "v0.2.0",
    releasedAt: "2026-10-20",
    items: [
      {
        title: "탐색으로 돌아오면 하던 대화가 그대로 있어요",
        description:
          "홈에 다녀와도 위쪽 「탐색」을 누르면 마지막으로 보던 대화가 열려요. 받던 답변도 이어서 보여요.",
        link: "CHAT",
      },
      {
        title: "다른 화면으로 옮기면 맨 위부터 보여요",
        description:
          "앞 화면에서 내린 자리에서 시작하지 않아요. 뒤로 가기로 돌아오면 보던 자리에서 이어서 볼 수 있어요.",
      },
    ],
  },
  {
    id: "v0.1.0",
    releasedAt: "2026-10-19",
    items: [
      {
        title: "마이크 권한이 꺼져 있으면 켜는 방법을 알려 드려요",
        description:
          "권한을 거부한 뒤 「다시 시도」를 눌러도 같은 안내만 뜨던 문제를 고쳤어요. 이제 브라우저에서 마이크를 허용하는 방법을 알려 드려요.",
        isFromFeedback: true,
      },
      {
        title: "문서 정리에 실패해도 다시 시도할 수 있어요",
        description:
          "녹음을 글로 옮기다 실패하면 정리 화면의 「다시 시도」로 처음부터 다시 맡길 수 있어요.",
      },
    ],
  },
  {
    id: "pr-1051",
    releasedAt: "2026-10-18",
    items: Object.values(imageReleaseNoteItems),
  },
] satisfies ReleaseNote[];

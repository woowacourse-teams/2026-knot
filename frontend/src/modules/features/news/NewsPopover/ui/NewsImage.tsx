import styled from "@emotion/styled";
import { useState } from "react";

interface NewsImageProps {
  /** 사진 주소. `NEWS_IMAGE_URL_PREFIX`로 시작하는 주소만 받아요 */
  src: string;
}

/**
 * 새 소식 항목의 사진 칸. 사진을 항목 폭에 맞춰 원래 비율로 보여 주고 자르지 않아요(NEWS-R13).
 *
 * 맞춘 높이가 240px을 넘으면 비율을 지킨 채 높이 240px로 줄이고 가운데에 둬요. 남는 양옆은 사진 칸 배경색이에요.
 * 사진을 다 불러오기 전과 불러오지 못했을 때는 사진 칸을 그리지 않아요(NEWS-R14).
 */
export default function NewsImage({ src }: NewsImageProps) {
  const [isLoaded, setIsLoaded] = useState(false);

  return (
    // 무엇이 바뀌었는지는 제목과 설명이 말해 주므로 사진은 꾸밈으로 둬요
    <Image
      src={src}
      alt=""
      hidden={!isLoaded}
      onLoad={() => setIsLoaded(true)}
    />
  );
}

const Image = styled.img`
  width: 100%;
  max-height: 15rem; /* 240px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[100]};
  object-fit: contain;

  /* 전역 스타일의 img display: block이 hidden을 덮어서 다시 숨겨요 */
  &[hidden] {
    display: none;
  }
`;

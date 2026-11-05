interface CanScrollContentParams {
  target: EventTarget | null;
  delta: number;
}

/** 기술 소개를 다 읽기 전에는 내부 스크롤을 먼저 쓰고, 끝에 닿으면 장면을 넘겨요. */
export const canScrollContent = ({ target, delta }: CanScrollContentParams) => {
  if (!(target instanceof Element)) return false;
  const content = target.closest<HTMLElement>("[data-landing-scroll]");
  if (!content || content.scrollHeight <= content.clientHeight) return false;
  if (delta < 0) return content.scrollTop > 1;
  if (delta > 0)
    return content.scrollTop + content.clientHeight < content.scrollHeight - 1;
  return false;
};

import styled from "@emotion/styled";

/**
 * 화면에는 보이지 않고 낭독기만 읽는 글자.
 *
 * 보이는 글자로 대신할 수 없는 안내(화면 이동 알림, 숫자 앞에 붙는 이름)나 시안에 없는 제목에 써요.
 * `display: none`과 달리 접근성 트리에 남아요. 다른 태그로 그려야 하면 `as`로 바꿔요. 예: `<VisuallyHidden as="h1">`
 */
const VisuallyHidden = styled.span`
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
`;

export default VisuallyHidden;

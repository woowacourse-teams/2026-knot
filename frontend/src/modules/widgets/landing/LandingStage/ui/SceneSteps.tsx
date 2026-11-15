import styled from "@emotion/styled";

import { focusRing, mobile } from "./SceneLayer";

interface SceneStepsProps {
  /** 장면 이름. 순서가 곧 장면 번호예요. */
  names: readonly string[];
  scene: number;
  onSceneSelect: (scene: number) => void;
}

/** 오른쪽에 세로로 놓인 장면 목록. 지금 장면을 표시하고, 누르면 그 장면으로 넘어가요. 모바일에서는 숨겨요. */
export default function SceneSteps({ names, scene, onSceneSelect }: SceneStepsProps) {
  return (
    <Container aria-label="섹션">
      {names.map((name, index) => (
        <Step
          key={name}
          type="button"
          aria-label={`${name} 섹션으로 이동`}
          aria-current={index === scene}
          onClick={() => onSceneSelect(index)}
        >
          <span>{name}</span>
          <Bar />
        </Step>
      ))}
    </Container>
  );
}

const Container = styled.nav`
  position: absolute;
  top: 50%;
  right: 1.75rem; /* 28px */
  z-index: 5;
  display: flex;
  flex-direction: column;
  transform: translateY(-50%);

  ${mobile} {
    display: none;
  }
`;

const Bar = styled.i`
  display: block;
  width: 0.5rem; /* 8px */
  height: 1.5px;
  background-color: ${({ theme }) => theme.neutral[300]};
  transition:
    width 0.3s,
    background-color 0.3s;
`;

const Step = styled.button`
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem; /* 8px */
  height: 2.5rem; /* 40px */
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
  transition: color 0.3s;
  ${({ theme }) => focusRing(theme)};

  &[aria-current="true"] {
    font-weight: 600;
    color: ${({ theme }) => theme.neutral[900]};

    i {
      width: 1.25rem; /* 20px */
      background-color: ${({ theme }) => theme.sub.accent[500]};
    }
  }
`;

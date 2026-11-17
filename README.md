[![Knot — 팀의 결정을 모두의 기억으로](frontend/public/og-image.png)](https://knoted.kr/)

# Knot

**팀의 결정을 모두의 기억으로**

회의를 녹음하면 결정만 골라 주제별 문서로 정리하고, 지난 결정도 질문으로 찾아보는 팀의 기록 공간이에요.

<p>
  <a href="https://knoted.kr/"><img src="https://img.shields.io/badge/Knot_시작하기-202623?style=for-the-badge" height="36" alt="Knot 서비스 바로가기" /></a>
</p>

## 서비스 소개

회의에서 함께 내린 결정도 시간이 지나면 기억이 흐려져요. 무엇을 정했는지, 왜 그렇게 정했는지 다시 확인하려면 긴 회의록을 읽거나 팀원에게 물어봐야 하죠.

**Knot**은 회의에서 나온 결정을 팀이 함께 꺼내 볼 수 있는 기록으로 연결해요. 회의를 녹음하고, 정리된 내용을 주제별 문서로 확인하고, 궁금한 지난 결정을 질문으로 찾아볼 수 있어요.

| 회의를 기록하고 | 주제별로 정리하고 | 지난 결정을 찾아봐요 |
| :--- | :--- | :--- |
| 회의를 녹음하고 전사와 요약을 확인해요. | 회의에서 나온 결정을 주제별 문서로 모아 봐요. | 질문을 통해 팀의 문서에서 필요한 내용을 찾아봐요. |

## 기술 스택

### 프론트엔드

| 역할 | 기술 |
| :--- | :--- |
| 언어 · UI | <img src="https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&amp;logo=typescript&amp;logoColor=white" height="28" alt="TypeScript" /> <img src="https://img.shields.io/badge/React-149ECA?style=for-the-badge&amp;logo=react&amp;logoColor=white" height="28" alt="React" /> <img src="https://img.shields.io/badge/Emotion-D36AC2?style=for-the-badge" height="28" alt="Emotion" /> |
| 상태 관리 | <img src="https://img.shields.io/badge/TanStack_Query-FF4154?style=for-the-badge&amp;logo=reactquery&amp;logoColor=white" height="28" alt="TanStack Query" /> <img src="https://img.shields.io/badge/Zustand-443E38?style=for-the-badge" height="28" alt="Zustand" /> |
| 빌드 | <img src="https://img.shields.io/badge/Webpack-1C78C0?style=for-the-badge&amp;logo=webpack&amp;logoColor=white" height="28" alt="Webpack" /> <img src="https://img.shields.io/badge/pnpm-F69220?style=for-the-badge&amp;logo=pnpm&amp;logoColor=white" height="28" alt="pnpm" /> |
| 테스트 · UI 개발 | <img src="https://img.shields.io/badge/Vitest-6E9F18?style=for-the-badge&amp;logo=vitest&amp;logoColor=white" height="28" alt="Vitest" /> <img src="https://img.shields.io/badge/Playwright-2EAD33?style=for-the-badge" height="28" alt="Playwright" /> <img src="https://img.shields.io/badge/Storybook-FF4785?style=for-the-badge&amp;logo=storybook&amp;logoColor=white" height="28" alt="Storybook" /> |
| 오류 추적 · 분석 | <img src="https://img.shields.io/badge/Sentry-362D59?style=for-the-badge&amp;logo=sentry&amp;logoColor=white" height="28" alt="Sentry" /> <img src="https://img.shields.io/badge/PostHog-000000?style=for-the-badge&amp;logo=posthog&amp;logoColor=white" height="28" alt="PostHog" /> |

### 백엔드

| 역할 | 기술 |
| :--- | :--- |
| 언어 · 프레임워크 | <img src="https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&amp;logo=openjdk&amp;logoColor=white" height="28" alt="Java" /> <img src="https://img.shields.io/badge/Spring_Boot-6DB33F?style=for-the-badge&amp;logo=springboot&amp;logoColor=white" height="28" alt="Spring Boot" /> |
| 인증 · 인가 | <img src="https://img.shields.io/badge/Spring_Security-6DB33F?style=for-the-badge&amp;logo=springsecurity&amp;logoColor=white" height="28" alt="Spring Security" /> |
| 데이터 관리 | <img src="https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=for-the-badge&amp;logo=spring&amp;logoColor=white" height="28" alt="Spring Data JPA" /> <img src="https://img.shields.io/badge/PostgreSQL-4169E1?style=for-the-badge&amp;logo=postgresql&amp;logoColor=white" height="28" alt="PostgreSQL" /> <img src="https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&amp;logo=flyway&amp;logoColor=white" height="28" alt="Flyway" /> |
| 빌드 | <img src="https://img.shields.io/badge/Gradle-02303A?style=for-the-badge&amp;logo=gradle&amp;logoColor=white" height="28" alt="Gradle" /> |
| 테스트 | <img src="https://img.shields.io/badge/JUnit-25A162?style=for-the-badge" height="28" alt="JUnit" /> <img src="https://img.shields.io/badge/Testcontainers-2496ED?style=for-the-badge&amp;logo=testcontainers&amp;logoColor=white" height="28" alt="Testcontainers" /> |
| 지표 수집 | <img src="https://img.shields.io/badge/Spring_Boot_Actuator-6DB33F?style=for-the-badge&amp;logo=springboot&amp;logoColor=white" height="28" alt="Spring Boot Actuator" /> <img src="https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&amp;logo=prometheus&amp;logoColor=white" height="28" alt="Prometheus" /> |

### 음성 처리

| 역할 | 기술 |
| :--- | :--- |
| 전사 | <img src="https://img.shields.io/badge/Whisper.cpp-202623?style=for-the-badge" height="28" alt="Whisper.cpp" /> |
| 화자 분리 | <img src="https://img.shields.io/badge/sherpa--onnx-202623?style=for-the-badge" height="28" alt="sherpa-onnx" /> |
| 오디오 처리 | <img src="https://img.shields.io/badge/FFmpeg-007808?style=for-the-badge&amp;logo=ffmpeg&amp;logoColor=white" height="28" alt="FFmpeg" /> |
| 실행 환경 | <img src="https://img.shields.io/badge/Java-ED8B00?style=for-the-badge&amp;logo=openjdk&amp;logoColor=white" height="28" alt="Java" /> <img src="https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&amp;logo=typescript&amp;logoColor=white" height="28" alt="TypeScript" /> <img src="https://img.shields.io/badge/Python-3776AB?style=for-the-badge&amp;logo=python&amp;logoColor=white" height="28" alt="Python" /> |

## 프로젝트 구조

| 폴더 | 내용 |
| :--- | :--- |
| [frontend](frontend/) | 회의 녹음, 문서 조회와 탐색을 제공하는 웹 애플리케이션 |
| [backend](backend/) | Spring Boot API 서버 |
| [ai-server](ai-server/) | 음성 전사와 화자 분리를 처리하는 서버 |
| [.github/workflows](.github/workflows/) | CI와 배포 워크플로 |

## 개발 문서

| 문서 | 내용 |
| :--- | :--- |
| [기여 가이드](CONTRIBUTING.md) | Issue·브랜치·PR 규칙과 코드 컨벤션 |
| [AI 서버 가이드](ai-server/README.md) | 음성 처리 서버 설치, 실행과 검증 |

## 팀원

### 프론트엔드

<table>
  <tr>
    <td align="center"><a href="https://github.com/geongyu09"><img src="https://avatars.githubusercontent.com/u/67491015?s=240&amp;v=4" width="120" height="120" alt="도넛 GitHub 프로필" /></a></td>
    <td align="center"><a href="https://github.com/vlmbuyd"><img src="https://avatars.githubusercontent.com/u/157789110?s=240&amp;v=4" width="120" height="120" alt="유월 GitHub 프로필" /></a></td>
    <td align="center"><a href="https://github.com/Eastar-DS"><img src="https://avatars.githubusercontent.com/u/72285245?s=240&amp;v=4" width="120" height="120" alt="이스타 GitHub 프로필" /></a></td>
  </tr>
  <tr>
    <td align="center"><a href="https://github.com/geongyu09">도넛</a></td>
    <td align="center"><a href="https://github.com/vlmbuyd">유월</a></td>
    <td align="center"><a href="https://github.com/Eastar-DS">이스타</a></td>
  </tr>
</table>

### 백엔드

<table>
  <tr>
    <td align="center"><a href="https://github.com/jyt6640"><img src="https://avatars.githubusercontent.com/u/82247738?s=240&amp;v=4" width="120" height="120" alt="흑곰 GitHub 프로필" /></a></td>
    <td align="center"><a href="https://github.com/poketopa"><img src="https://avatars.githubusercontent.com/u/96972872?s=240&amp;v=4" width="120" height="120" alt="루덴스 GitHub 프로필" /></a></td>
  </tr>
  <tr>
    <td align="center"><a href="https://github.com/jyt6640">흑곰</a></td>
    <td align="center"><a href="https://github.com/poketopa">루덴스</a></td>
  </tr>
</table>

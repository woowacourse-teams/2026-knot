# README 다이어그램

운영(prod)의 핵심 서비스 흐름과 CI/CD를 설명한다. PNG와 Excalidraw 원본은 같은 요소 정의에서 생성했다. 아이콘은 PNG 파일을 직접 삽입했으며, Excalidraw 원본에도 이미지 데이터를 포함해 외부 이미지 요청 없이 편집할 수 있다.

## 확인 기준과 범위

- 코드 기준: `beacbf2a0c69476af529a5a626db83e8a33a6eb2` (`main`, 2026-11-17 확인).
- AI 구성: 사용자가 2026-11-17 확인한 **맥미니 Whisper + 로컬 LLM**을 반영했다. 로컬 LLM 연결은 저장소의 LM Studio 어댑터와 설정을 근거로 한다. 특정 모델명·파라미터·하드웨어 사양은 표시하지 않았다.
- AWS 구성: 저장소의 배포 설정과 기존 운영 검증 기록을 대조했다. 이번 AWS 실시간 조회는 자격 증명 오류로 실패했으므로, 현재 배포 revision·리소스 개수·실행 상태를 새로 검증했다는 의미는 아니다.
- 아키텍처는 논리적 요청 관계다. 화살표의 반대 방향 응답은 생략했다. 네트워크 연결 방식·VPC·서브넷·보안 그룹·터널·가용 영역을 추정해서 추가하지 않았다.
- 인증·Notion 연동·오류 추적·분석·관측 등 보조 경로는 생략했다. 전체 시스템의 모든 외부 의존성을 나열하는 그림은 아니다.
- EC2의 Blue·Green은 같은 인스턴스 안의 슬롯이다. 두 서버가 상시 가동되거나 부하를 분산한다는 의미가 아니다.
- PR CI와 운영 CD는 별도 이벤트로 실행된다. CI가 성공해야 CD가 시작되는 `workflow_run` 연결을 가정하지 않았다. 브랜치 보호 규칙의 필수 검사 설정은 이 그림에 포함하지 않았다.
- CI 그림은 PR 검증과 운영 CD에 초점을 맞췄다. push 시의 추가 CI, Storybook 미리보기, Governance·Issue 자동화, dev 배포는 생략했다. AI 서버의 자동 배포 경로도 만들어 넣지 않았다.

그림은 흰 배경·실제 PNG 아이콘·짧은 이름·연결선으로 구성했다. Mac mini에는 장비를 뜻하는 일반 서버 아이콘을 사용했다. 실제 장비 사진이나 Apple 공식 제품 아이콘은 아니다. 화살표는 요청 방향이며 응답은 생략했다. PR 검증의 구체적인 영역별 검사와 실행 조건은 아래 근거를 따른다.

## 구성별 근거

| 표현 | 확인한 저장소 근거 |
| --- | --- |
| Cloudflare Workers가 웹 앱 제공 | [wrangler.jsonc](../../frontend/wrangler.jsonc)의 정적 assets와 prod 환경 |
| API의 Cloudflare·Nginx·EC2 경로 | [공통 배포 hook](../../backend/scripts/bluegreen_deploy.sh)의 운영 Origin TLS 검증·Nginx upstream, [운영 배포 검증 기록](../../backend/docs/operations/prod-v060-deployment-20261026.md) |
| EC2 한 대의 Blue/Green 슬롯 | [운영 AppSpec](../../backend/appspec-prod-bluegreen.yml), [운영 hook](../../backend/scripts/prod_bluegreen_deploy.sh), 위 운영 배포 기록 |
| PostgreSQL RDS | 위 운영 배포 기록의 Prod RDS 검증, [애플리케이션 설정](../../backend/src/main/resources/application.properties) |
| 브라우저 → S3 직접 업로드 | [PCM 업로드 큐](../../frontend/src/shared/store/recordingStore/pcmChunkUploadQueue.ts)의 업로드 URL을 통한 PUT |
| 백엔드 → 맥미니 요청·조회, 맥미니 → S3 다운로드 | [AI 서버 HTTP 계약](../../ai-server/docs/http-contract.md), [AI 서버 가이드](../../ai-server/README.md) |
| 로컬 LLM 호출 | [애플리케이션 설정](../../backend/src/main/resources/application.properties)의 LLM provider/base URL, [공급자 전환 가이드](../../backend/ops/demo-cloud/README.md)와 사용자 확인 |
| FE PR 검증 | [Frontend CI](../../.github/workflows/frontend-ci.yml), [Frontend E2E](../../.github/workflows/frontend-e2e.yml). E2E는 실제 운영 API 대신 MSW를 사용하는 브라우저 테스트 |
| BE PR 병렬 검증 | [Backend CI](../../.github/workflows/backend-ci.yml)의 integration·remaining jobs |
| AI PR 검증 | [AI Server CI](../../.github/workflows/ai-server-ci.yml) |
| FE 운영 배포 | [Frontend Prod CD](../../.github/workflows/deploy-frontend-prod.yml). main의 frontend 또는 해당 workflow 변경 push, 또는 수동 실행 |
| BE main → CodePipeline → CodeBuild → S3 → CodeDeploy | [AWS 환경 기록](../../backend/docs/operations/aws-dev-and-prod-environments.md), [운영 배포 검증 기록](../../backend/docs/operations/prod-v060-deployment-20261026.md), [buildspec](../../buildspec.yml), [패키징 스크립트](../../backend/ops/codebuild/package-deployment-artifact.sh) |

## 편집과 다시 내보내기

- `architecture-prod.excalidraw`와 `ci-cd-prod.excalidraw`는 Excalidraw의 파일 열기로 편집할 수 있다. Obsidian Excalidraw에서도 해당 파일을 가져올 수 있다. 각 서비스의 아이콘과 이름은 그룹으로 묶었다.
- `render_diagrams.py`는 아이콘·선·텍스트 좌표를 정의하고 PNG와 Excalidraw를 함께 생성한다. Excalidraw에서 직접 수정한 내용은 이 스크립트에 역으로 반영되지 않으므로, 직접 수정한 원본을 다시 생성해 덮어쓰지 않는다.
- PNG는 2배 해상도, 불투명 배경이다. GitHub의 밝은 테마와 어두운 테마에서 동일하게 보인다.
- 생성에는 Python 3와 Pillow가 필요하다. 기본 글꼴은 macOS의 Apple SD Gothic Neo다. 다른 환경에서는 한글 지원 글꼴 경로를 `DIAGRAM_FONT` 환경 변수로 지정한다.

```sh
python3 docs/readme/render_diagrams.py
```

## 아이콘 출처

아이콘은 각 제품·프로젝트의 표식이며 Knot의 자체 로고가 아니다.

| 파일 | 출처 |
| --- | --- |
| `ec2.png`, `rds.png`, `s3.png`, `codepipeline.png`, `codebuild.png`, `codedeploy.png` | [AWS 공식 Architecture Icons](https://aws.amazon.com/architecture/icons/), 2026-07-31 패키지의 `64@5x.png`. AWS가 제공하는 아키텍처 다이어그램 용도 |
| `cloudflare.png` | [Diagrams의 Cloudflare PNG](https://github.com/mingrammer/diagrams/blob/master/resources/saas/cdn/cloudflare.png) |
| `github.png` | [Diagrams의 GitHub PNG](https://github.com/mingrammer/diagrams/blob/master/resources/onprem/vcs/github.png) |
| `client.png` | [Diagrams의 클라이언트 PNG](https://github.com/mingrammer/diagrams/blob/master/resources/onprem/client/client.png) |
| `nginx.png` | [Diagrams의 Nginx PNG](https://github.com/mingrammer/diagrams/blob/master/resources/onprem/network/nginx.png) |
| `spring.png` | [Diagrams의 Spring PNG](https://github.com/mingrammer/diagrams/blob/master/resources/programming/framework/spring.png) |
| `github-actions.png` | [Diagrams의 GitHub Actions PNG](https://github.com/mingrammer/diagrams/blob/master/resources/onprem/ci/github-actions.png) |
| `server.png` | [Diagrams의 일반 서버 PNG](https://github.com/mingrammer/diagrams/blob/master/resources/onprem/compute/server.png) |

Diagrams의 소프트웨어 라이선스는 [MIT](https://github.com/mingrammer/diagrams/blob/master/LICENSE)이며, 제품 로고의 권리는 각각의 권리자에게 있다. 아이콘은 2026-11-17에 내려받았다.

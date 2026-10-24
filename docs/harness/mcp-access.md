# 노션과 Figma MCP 접근 설정

`knot-feature` 흐름의 서브 에이전트는 노션(기능 요구사항, 도메인 규칙, API 명세, QA 시트)과
Figma에 직접 읽고 쓴다. 팀원 누구의 에이전트든 같은 정본에 닿아야 흐름이 돈다. 이 문서는 그
접근을 어떻게 여는지 적는다. 토큰 값은 이 문서, 저장소, 노션 어디에도 적지 않는다.

- 적용 범위: Claude Code와 Codex, 저장소 루트 세션
- 설정 파일: 저장소 `.mcp.json`(Claude Code 프로젝트 범위, 비밀값 없음), 각자의 `~/.codex/config.toml`
- 상태: 2026-10-22 작성. 노션은 내부 통합 토큰, Figma는 각자 로그인

## 현재 제약

| 대상 | 사실 | 근거 |
| --- | --- | --- |
| 노션 호스팅 MCP(`mcp.notion.com`) | 워크스페이스 멤버만 연결할 수 있다. 게스트는 승인이 거부된다. 비대화형 MCP 토큰은 Business 이상에서만 만든다. | Notion 개발자 문서 「Get started with MCP」 |
| knot 노션 | 문서는 유월의 개인 워크스페이스 아래에 있고 팀원은 게스트다. | 운영 현황 |
| 노션 내부 통합 | 워크스페이스 소유자가 만들고 페이지에 연결하면 그 하위 전체를 읽고 쓸 수 있다. 오픈소스 `@notionhq/notion-mcp-server`가 이 토큰을 쓴다. 적극 유지보수되지 않는다는 안내가 있다. | Notion 개발자 문서 |
| Figma 원격 MCP(`mcp.figma.com`) | 모든 플랜에서 쓴다. View와 Collab 시트는 월 20회, Professional 이상의 Full과 Dev 시트는 하루 200회, Education 플랜은 Professional과 같다. 파일 권한이 그대로 적용된다. | Figma 개발자 문서 「Rate limits and access」 |
| Figma 데스크톱 MCP(`127.0.0.1:3845`) | 유료 플랜의 Dev 또는 Full 시트가 필요하다. | Figma 개발자 문서 |

결론: 지금은 노션은 유월이 만든 내부 통합 토큰 하나를 팀이 공유하고, Figma는 각자 자기 계정으로
로그인한다. 팀원 쓰기가 사람 이름으로 남아야 하거나 knot 문서가 팀원이 멤버인 워크스페이스로
옮겨지면 노션도 각자 로그인(OAuth)으로 바꾼다.

## 1. 노션 내부 통합 토큰 만들기 (유월, 한 번)

1. https://www.notion.so/profile/integrations 를 연다. 워크스페이스 설정의 「연결」에서
   「통합 개발 또는 관리」로도 간다.
2. 「새 통합」을 누른다. 이름은 `knot 하네스`, 연결 워크스페이스는 knot 문서가 있는 워크스페이스,
   유형은 내부(Internal)다.
3. 기능(Capabilities)에서 콘텐츠 읽기, 업데이트, 삽입을 켠다. 댓글 읽기와 삽입도 켠다(다른 영역
   담당자에게 남기는 @멘션 댓글). 사용자 정보는 「이메일 없이 읽기」로 둔다(담당자 칸 지정에 필요).
4. 저장하고 「내부 통합 시크릿」을 복사한다. `ntn_`으로 시작한다. 이 값이 토큰이다.
5. 통합을 페이지에 연결한다. 노션에서 knot 최상위 페이지(기획 문서 모음, (신)도메인, (신)API
   명세서를 품은 `knot` 페이지)를 열고 오른쪽 위 「…」, 「연결」, `knot 하네스`를 고른다. 하위
   페이지와 DB는 상속된다. 기획서(V2)가 그 아래에 없으면 그 페이지에도 따로 연결한다.
6. 확인: 아래 「확인」 절의 명령으로 기능 요구사항 DB를 한 번 읽어 본다.

토큰이 새면 같은 화면의 「시크릿 새로 고침」으로 바꾸고 팀에 다시 전달한다. 통합을 삭제하면 모든
접근이 끊긴다.

## 2. 토큰 전달 방법

- 전달은 비밀번호 관리자의 공유 금고(1Password, Bitwarden)나 한 번만 열리는 비밀 링크로 한다.
  Discord 채널, 노션 페이지, 이슈, 커밋, 스크린샷에는 넣지 않는다.
- 받은 사람은 자기 셸 환경 변수로만 둔다. `~/.zshenv`에 다음 한 줄을 넣고 새 터미널을 연다.

  ```sh
  export NOTION_TOKEN="ntn_..."
  ```

- 저장소 `.mcp.json`은 값을 담지 않고 `${NOTION_TOKEN}`으로 환경 변수를 읽는다. `.env`, `.env.local`
  같은 파일을 저장소 안에 만들지 않는다.
- CI(GitHub Actions)가 노션에 쓸 일이 생기면 사람용 토큰을 재사용하지 않고 통합을 하나 더 만들어
  GitHub Secrets에 넣는다.

## 3. Claude Code 설정 (팀원 각자)

1. 저장소 루트에서 `claude`를 연다. 프로젝트 `.mcp.json`의 `knot-notion`, `figma` 서버를 쓸지 묻는
   화면에서 승인한다.
2. `/mcp`를 열어 `figma`에 로그인한다. 브라우저에서 자기 Figma 계정으로 승인한다. Figma 쪽
   권한은 그 계정이 knot 파일에 가진 권한과 같다. 구현 단계에서 디자인을 읽으려면 「볼 수 있음」,
   디자인 단계에서 Figma를 고치려면 「편집 가능」이 있어야 한다.
3. `knot-notion`은 환경 변수 `NOTION_TOKEN`이 있으면 바로 연결된다. `claude mcp list`로 두 서버가
   연결됐는지 본다.
4. 노션에 쓰는 흐름은 Claude Code 안에서 서브 에이전트가 수행하므로 따로 할 일이 없다. 연결이
   없는 세션에서는 해당 서브 에이전트가 `blocked`로 돌아온다.

서버 이름이 `notion`이 아니라 `knot-notion`인 이유: 자기 노션 계정으로 이미 연결한 `notion`(mcp.notion.com)
서버와 이름이 같으면 저장소 안에서 프로젝트 설정이 개인 설정을 덮어 자기 연결이 사라진다. 유월처럼
워크스페이스 멤버 로그인으로 연결된 사람은 토큰 없이 `knot-notion` 승인을 거절하고 자기 연결을 그대로 쓴다.

headless `claude -p` 실행도 같은 `.mcp.json`을 읽는다. Figma 로그인은 그 기기에서 한 번 해 두면
된다.

## 4. Codex 설정 (팀원 각자)

Codex는 프로젝트 설정보다 개인 설정 파일을 쓴다. `~/.codex/config.toml`에 다음을 넣고 파일 권한을
`600`으로 둔다. 이 파일은 저장소 밖 개인 파일이다.

```toml
[mcp_servers.knot-notion]
command = "npx"
args = ["-y", "@notionhq/notion-mcp-server"]
env = { NOTION_TOKEN = "ntn_..." }

[mcp_servers.figma]
url = "https://mcp.figma.com/mcp"
```

그 뒤 `codex mcp login figma`로 Figma에 로그인하고 `codex mcp list`로 확인한다.

## 5. Figma 플랜과 시트 확인

- 구현 단계는 컴포넌트마다 Figma URL을 읽어 스토리북에 넣으므로 호출 수가 적지 않다. View나
  Collab 시트의 월 20회로는 부족하다. 팀원 모두 Professional 이상의 Dev 또는 Full 시트가 있어야
  하고, 학생은 Education 플랜으로 같은 한도를 받는다.
- 확인: Figma 팀 설정의 플랜과 각자의 시트를 본다. 부족하면 Education 신청이나 시트 조정이
  먼저다.
- Figma 쓰기(디자인 단계)는 변경 표시 규칙(`knot-ux-journal`)을 그대로 따른다.

## 확인

```sh
# 노션: 토큰이 knot 페이지에 닿는지 (기능 요구사항 DB 조회)
curl -s https://api.notion.com/v1/databases/3c2b435175228098afd2c1c9fdef48ba \
  -H "Authorization: Bearer $NOTION_TOKEN" -H "Notion-Version: 2022-06-28" | head -c 300

# Claude Code: 두 서버 연결 상태
claude mcp list
```

첫 줄이 `"object":"database"`로 시작하면 연결된 것이다. `object":"error"`면 통합이 그 페이지에
연결되지 않았거나 토큰이 틀린 것이다.

## 다음 단계 (지금 하지 않음)

- 팀원이 멤버인 워크스페이스로 knot 문서를 옮기거나 멤버로 초대하면 `.mcp.json`의 `knot-notion`을
  `{"type": "http", "url": "https://mcp.notion.com/mcp"}`로 바꾸고 각자 `/mcp`로 로그인한다. 그러면
  노션 쓰기가 사람 이름으로 남고 공유 토큰이 사라진다.
- `@notionhq/notion-mcp-server`가 멈추면 같은 토큰을 쓰는 다른 노션 MCP 서버로 바꾼다. 토큰과
  연결 방식은 그대로다.

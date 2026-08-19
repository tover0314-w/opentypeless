<p align="center">
  <a href="README.md">English</a> | <a href="README_zh.md">中文</a> | <a href="README_ja.md">日本語</a> | <strong>한국어</strong> | <a href="README_es.md">Español</a> | <a href="README_fr.md">Français</a> | <a href="README_de.md">Deutsch</a> | <a href="README_pt.md">Português</a> | <a href="README_ru.md">Русский</a> | <a href="README_ar.md">العربية</a> | <a href="README_hi.md">हिन्दी</a> | <a href="README_it.md">Italiano</a> | <a href="README_tr.md">Türkçe</a> | <a href="README_vi.md">Tiếng Việt</a> | <a href="README_th.md">ภาษาไทย</a> | <a href="README_id.md">Bahasa Indonesia</a> | <a href="README_pl.md">Polski</a> | <a href="README_nl.md">Nederlands</a>
</p>

<p align="center">
  <img src="src-tauri/icons/128x128@2x.png" width="128" height="128" alt="OpenTypeless 로고" />
</p>

<h1 align="center">OpenTypeless</h1>

<p align="center">
  데스크톱을 위한 오픈소스 AI 음성 입력. 자연스럽게 말하면, 모든 앱에서 다듬어진 텍스트를 얻으세요.
</p>

<p align="center">
  이메일 작성, 코딩, 채팅, 메모 등 — 단축키를 누르고<br/>
  생각을 말하면 OpenTypeless가 AI로 음성을 전사하고 텍스트를 다듬어<br/>
  사용 중인 앱에 직접 입력합니다.
</p>

<p align="center">
  <a href="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml"><img src="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/releases"><img src="https://img.shields.io/github/v/release/tover0314-w/opentypeless?color=2ABBA7" alt="릴리스" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tover0314-w/opentypeless" alt="라이선스" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/stargazers"><img src="https://img.shields.io/github/stars/tover0314-w/opentypeless?style=social" alt="Stars" /></a>
  <a href="https://discord.gg/V6rRpJ4RGD"><img src="https://img.shields.io/badge/Discord-Join%20us-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

<p align="center">
  <img src="docs/images/v1.1.49-app-context-showcase.jpg" width="820" alt="Gmail, Slack, Google Docs, Cursor, Zendesk, LinkedIn에서 동작하는 OpenTypeless 앱 인식 음성 입력" />
</p>

<p align="center">
  <img src="docs/images/voice-flow-demo.gif" width="720" alt="OpenTypeless 데모" />
</p>

## v1.1.49의 새로운 기능

- **앱 인식 글쓰기**는 현재 앱을 로컬에서 감지하고 이메일, 채팅, 문서, 이슈 트래커, 개발 도구 등에 맞게 구조와 어조를 조정합니다.
- **음성 의도 라우팅**은 일반 받아쓰기, 선택 텍스트 편집, 번역, Ask Anything, 지원되는 음성 작업을 영어, 중국어 간체 및 번체에서 구분합니다.
- **워크플로별 여러 단축키**를 사용해 받아쓰기, Ask Anything, 번역에 둘 이상의 키 조합을 추가하고 순서를 바꿀 수 있습니다.
- **전환 가능한 번역 대상**으로 출력 언어를 하나로 고정하지 않고 자주 쓰는 언어 사이를 빠르게 바꿀 수 있습니다.
- **강화된 로컬 사전**은 수정 규칙과 사전 가져오기/내보내기를 지원합니다.
- **앱별 스타일 매핑**으로 기본 앱 분류를 재정의하고 특정 앱에 다른 글쓰기 스타일을 지정할 수 있습니다.

앱 감지, 매핑, 사전 항목 및 수정 규칙은 모두 로컬에 저장됩니다. 앱 인식 다듬기는 내부 앱 분류와 승인된 스타일 메타데이터만 설정한 LLM 경로로 전송합니다. 원본 창 제목과 문서 내용은 앱 컨텍스트로 전송되거나 기록에 저장되지 않습니다.

| 앱 인식 AI 다듬기 | 로컬 사전 및 수정 규칙 |
| --- | --- |
| <img src="docs/images/v1.1.49-app-aware-polish.jpg" width="420" alt="OpenTypeless v1.1.49 앱 인식 AI 다듬기" /> | <img src="docs/images/v1.1.49-dictionary.jpg" width="420" alt="OpenTypeless v1.1.49 로컬 사전 및 수정 규칙" /> |

<details>
<summary>더 많은 스크린샷</summary>

<p align="center">
  <img src="docs/images/app-main-light.png" width="720" alt="OpenTypeless 메인 윈도우" />
</p>

| 설정 | 기록 |
|---|---|
| <img src="docs/images/app-settings.png" width="360" /> | <img src="docs/images/app-history.png" width="360" /> |

</details>

---

## 왜 OpenTypeless인가?

| | OpenTypeless | macOS Dictation | Windows Voice Typing | Whisper Desktop |
|---|---|---|---|---|
| AI 텍스트 다듬기 | ✅ 다중 LLM | ❌ | ❌ | ❌ |
| STT 제공자 선택 | ✅ 6개 이상 제공자 | ❌ Apple만 | ❌ Microsoft만 | ❌ Whisper만 |
| 모든 앱에서 작동 | ✅ | ✅ | ✅ | ❌ 복사-붙여넣기 |
| 번역 모드 | ✅ | ❌ | ❌ | ❌ |
| 오픈 소스 | ✅ MIT | ❌ | ❌ | ✅ |
| 크로스 플랫폼 | ✅ Win/Mac/Linux | ❌ Mac만 | ❌ Windows만 | ✅ |
| 사용자 사전 | ✅ | ❌ | ❌ | ❌ |
| 셀프 호스팅 가능 | ✅ BYOK | ❌ | ❌ | ✅ |

## 기능

- 🎙️ 글로벌 단축키 녹음 — 길게 눌러 녹음 또는 토글 모드
- 💊 항상 위에 표시되는 플로팅 캡슐 위젯
- 🗣️ 6개 이상의 STT 제공자: Deepgram, AssemblyAI, Whisper, Groq, GLM-ASR, SiliconFlow
- 🤖 다양한 LLM으로 텍스트 다듬기: OpenAI, DeepSeek, Claude, Gemini, Ollama 등
- ⚡ 스트리밍 출력 — LLM이 생성하는 대로 텍스트 표시
- ⌨️ 키보드 시뮬레이션 또는 클립보드 출력
- 📝 녹음 전 텍스트를 선택하면 LLM에 컨텍스트로 전달
- 🌐 번역 모드: 한 언어로 말하고 다른 언어로 출력 (20+ 언어)
- 📖 전문 용어를 위한 사용자 사전
- 🔍 앱별 감지로 포맷 자동 조정
- 📜 전체 텍스트 검색이 가능한 로컬 기록
- 🌗 다크 / 라이트 / 시스템 테마
- 🚀 로그인 시 자동 시작

> [!TIP]
> **최고의 경험을 위한 추천 설정**
>
> | | 제공자 | 모델 |
> |---|---|---|
> | 🗣️ STT | Groq | `whisper-large-v3-turbo` |
> | 🤖 AI 다듬기 | Google | `gemini-2.5-flash` |
>
> 이 조합은 빠르고 정확한 전사와 고품질 텍스트 다듬기를 제공하며, 둘 다 넉넉한 무료 티어를 제공합니다.

## 다운로드

플랫폼에 맞는 최신 버전을 다운로드하세요:

**[Releases에서 다운로드](https://github.com/tover0314-w/opentypeless/releases)**

| 플랫폼 | 파일 |
|--------|------|
| Windows | `.msi` 설치 프로그램 |
| macOS (Apple Silicon) | `.dmg` |
| macOS (Intel) | `.dmg` |
| Linux | `.AppImage` / `.deb` |

## 사전 요구사항

- [Node.js](https://nodejs.org/) 20+
- [Rust](https://rustup.rs/) (stable 툴체인)
- Tauri 플랫폼별 의존성: [Tauri 사전 요구사항](https://v2.tauri.app/start/prerequisites/) 참조

## 시작하기

```bash
# 의존성 설치
npm install

# 개발 모드 실행
npm run tauri dev

# 프로덕션 빌드
npm run tauri build
```

빌드된 애플리케이션은 `src-tauri/target/release/bundle/`에 위치합니다.

## 설정

모든 설정은 앱 내 설정 패널에서 접근할 수 있습니다:

- **음성 인식** — STT 제공자를 선택하고 API 키를 입력
- **AI 다듬기** — LLM 제공자, 모델, API 키를 선택
- **일반** — 단축키, 출력 모드, 테마, 자동 시작
- **사전** — 더 나은 전사 정확도를 위한 사용자 용어 추가
- **장면** — 다양한 사용 사례를 위한 프롬프트 템플릿

API 키는 `tauri-plugin-store`를 통해 로컬에 저장됩니다. OpenTypeless 서버로 키가 전송되지 않습니다 — 모든 STT/LLM 요청은 설정한 제공자에게 직접 전송됩니다.

### Cloud (Pro) 옵션

OpenTypeless는 자체 API 키 없이도 관리형 STT 및 LLM 할당량을 제공하는 선택적 Pro 구독도 제공합니다. 이는 완전히 선택 사항입니다 — 앱은 자체 키만으로도 완전히 작동합니다.

[Pro에 대해 자세히 알아보기](https://www.opentypeless.com)

### BYOK (Bring Your Own Key) vs Cloud

| | BYOK 모드 | Cloud (Pro) 모드 |
|---|---|---|
| STT | 자체 API 키 (Deepgram, AssemblyAI 등) | 관리형 할당량 (10시간/월) |
| LLM | 자체 API 키 (OpenAI, DeepSeek 등) | 관리형 할당량 (~50만 토큰/월) |
| 클라우드 의존성 | 없음 — 모든 요청이 제공자에게 직접 전송 | www.opentypeless.com 연결 필요 |
| 비용 | 제공자에게 직접 지불 | $4.99/월 구독 |

모든 핵심 기능 — 녹음, 전사, AI 다듬기, 키보드/클립보드 출력, 사전, 기록 — 은 BYOK 모드에서 OpenTypeless 서버 없이 완전히 오프라인으로 작동합니다.

### 셀프 호스팅 / 클라우드 없이 사용

클라우드 의존성 없이 OpenTypeless를 실행하려면:

1. 설정에서 Cloud가 아닌 STT 및 LLM 제공자를 선택
2. 자체 API 키를 입력
3. 끝 — www.opentypeless.com에 대한 계정이나 인터넷 연결이 필요하지 않습니다

선택적 클라우드 기능을 자체 백엔드로 연결하려면 빌드 전에 다음 환경 변수를 설정하세요:

| 변수 | 기본값 | 설명 |
|---|---|---|
| `VITE_API_BASE_URL` | `https://www.opentypeless.com` | 프론트엔드 클라우드 API 기본 URL |
| `API_BASE_URL` | `https://www.opentypeless.com` | Rust 백엔드 클라우드 API 기본 URL |

```bash
# 예시: 커스텀 백엔드로 빌드
VITE_API_BASE_URL=https://my-server.example.com API_BASE_URL=https://my-server.example.com npm run tauri build
```

## 아키텍처

**데이터 흐름 파이프라인:**

```
마이크 → 오디오 캡처 → STT 제공자 → 원시 전사 → LLM 다듬기 → 키보드/클립보드 출력
```

```
src/                  # React 프론트엔드 (TypeScript)
├── components/       # UI 컴포넌트 (설정, 기록, 캡슐 등)
├── hooks/            # React 훅 (녹음, 테마, Tauri 이벤트)
├── lib/              # 유틸리티 (API 클라이언트, 라우터, 상수)
└── stores/           # Zustand 상태 관리

src-tauri/src/        # Rust 백엔드
├── audio/            # cpal을 통한 오디오 캡처
├── stt/              # STT 제공자 (Deepgram, AssemblyAI, Whisper 호환, Cloud)
├── llm/              # LLM 제공자 (OpenAI 호환, Cloud)
├── output/           # 텍스트 출력 (키보드 시뮬레이션, 클립보드 붙여넣기)
├── storage/          # 설정 (tauri-plugin-store) + 기록/사전 (SQLite)
├── app_detector/     # 컨텍스트를 위한 활성 애플리케이션 감지
├── pipeline.rs       # 녹음 → STT → LLM → 출력 오케스트레이션
└── lib.rs            # Tauri 앱 설정, 명령, 단축키 처리
```

## 로드맵

- [ ] 커스텀 STT/LLM 통합을 위한 플러그인 시스템
- [ ] 다국어 STT 정확도 및 방언 지원 개선
- [ ] 음성 명령
- [ ] 사용자 정의 단축키 조합
- [ ] 온보딩 경험 개선
- [ ] 모바일 컴패니언 앱

## 자주 묻는 질문

**내 오디오가 클라우드로 전송되나요?**
BYOK 모드에서는 오디오가 선택한 STT 제공자(예: Groq, Deepgram)에게 직접 전송됩니다. OpenTypeless 서버를 거치지 않습니다. Cloud(Pro) 모드에서는 오디오가 관리형 프록시로 전송되어 전사됩니다.

**오프라인에서 사용할 수 있나요?**
로컬 STT 제공자(Ollama를 통한 Whisper)와 로컬 LLM(Ollama)을 사용하면 완전히 오프라인으로 작동합니다. 인터넷 연결이 필요 없습니다.

**어떤 언어가 지원되나요?**
STT는 제공자에 따라 99개 이상의 언어를 지원합니다. AI 다듬기와 번역은 20개 이상의 대상 언어를 지원합니다.

**앱은 무료인가요?**
네. 자체 API 키(BYOK)로 모든 기능을 사용할 수 있습니다. Cloud Pro 구독($4.99/월)은 선택 사항입니다.

## 커뮤니티

- 💬 [Discord](https://discord.gg/V6rRpJ4RGD) — 대화, 도움 받기, 피드백 공유
- 🗣️ [GitHub Discussions](https://github.com/tover0314-w/opentypeless/discussions) — 기능 제안, 질문과 답변
- 🐛 [Issue Tracker](https://github.com/tover0314-w/opentypeless/issues) — 버그 보고 및 기능 요청
- 📖 [기여 가이드](CONTRIBUTING.md) — 개발 설정 및 가이드라인
- 🔒 [보안 정책](SECURITY.md) — 취약점을 책임감 있게 보고
- 🧭 [비전](VISION.md) — 프로젝트 원칙 및 로드맵 방향

## 기여하기

기여를 환영합니다! 개발 설정 및 가이드라인은 [CONTRIBUTING.md](CONTRIBUTING.md)를 참조하세요.

시작할 곳을 찾고 계신가요? [`good first issue`](https://github.com/tover0314-w/opentypeless/labels/good%20first%20issue) 라벨이 붙은 이슈를 확인하세요.

## Star History

<a href="https://star-history.dera.page/#tover0314-w/opentypeless&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date" />
    <img alt="Star History 차트" src="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date" />
  </picture>
</a>

## Claude Code로 하루 만에 개발

이 프로젝트 전체는 [Claude Code](https://claude.com/claude-code)를 사용하여 하루 만에 구축되었습니다 — 아키텍처 설계부터 완전한 구현까지, Tauri 백엔드, React 프론트엔드, CI/CD 파이프라인, 이 README를 포함합니다.

## 라이선스

[MIT](LICENSE)

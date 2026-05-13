<p align="center">
  <a href="README.md">English</a> | <strong>中文</strong> | <a href="README_ja.md">日本語</a> | <a href="README_ko.md">한국어</a> | <a href="README_es.md">Español</a> | <a href="README_fr.md">Français</a> | <a href="README_de.md">Deutsch</a> | <a href="README_pt.md">Português</a> | <a href="README_ru.md">Русский</a> | <a href="README_ar.md">العربية</a> | <a href="README_hi.md">हिन्दी</a> | <a href="README_it.md">Italiano</a> | <a href="README_tr.md">Türkçe</a> | <a href="README_vi.md">Tiếng Việt</a> | <a href="README_th.md">ภาษาไทย</a> | <a href="README_id.md">Bahasa Indonesia</a> | <a href="README_pl.md">Polski</a> | <a href="README_nl.md">Nederlands</a>
</p>

<p align="center">
  <img src="src-tauri/icons/128x128@2x.png" width="128" height="128" alt="OpenTypeless Logo" />
</p>

<h1 align="center">OpenTypeless v2.0</h1>

<p align="center">
  开源桌面端 AI 语音输入工具。自然说话，在任意应用中获得润色后的文本。
</p>

<p align="center">
  无论你在写邮件、写代码、聊天还是做笔记 — 只需按下 <code>Ctrl+Win</code>，<br/>
  说出你的想法，OpenTypeless 会用 AI 转录并润色你的语音，<br/>
  然后直接输入到你正在使用的任何应用中。
</p>

<p align="center">
  <a href="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml"><img src="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/releases"><img src="https://img.shields.io/github/v/release/tover0314-w/opentypeless?color=2ABBA7" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tover0314-w/opentypeless" alt="License" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/stargazers"><img src="https://img.shields.io/github/stars/tover0314-w/opentypeless?style=social" alt="Stars" /></a>
</p>

---

## v2.0 新增功能

### ⌨️ 全新快捷键：`Ctrl+Win`
默认快捷键改为 **`Ctrl+Win`**，单手即可操作。按住录音，松开输出，流畅自然。采用 Windows 底层键盘钩子（`WH_KEYBOARD_LL`）实现——与 Wispr Flow 相同的技术方案，解决传统 `RegisterHotKey` 无法处理纯修饰键组合的局限。

### 🔧 快捷键自定义修复
- **快捷键录制器** 支持纯修饰键组合（如 `Ctrl+Win`、`Ctrl+Shift`）
- 修复了切换设置页面时快捷键卡死、无法唤醒的 Bug
- 组件卸载时自动恢复已注册的快捷键

### ⚡ 流式分段处理（Streaming）
- 支持 Deepgram / AssemblyAI 的 VAD（语音活动检测）分段
- 每段语音独立进行 LLM 润色和输出，延迟更低
- 实时分段计数显示在胶囊悬浮窗上

### 🧩 技术栈

| 层次 | 技术 | 用途 |
|------|------|------|
| **桌面框架** | Tauri v2 | 跨平台桌面壳、系统托盘、全局快捷键、原生窗口 |
| **前端** | React 19 + TypeScript | UI 界面（设置、历史、悬浮胶囊） |
| **状态管理** | Zustand | 前端全局状态（pipeline、配置、历史） |
| **样式** | Tailwind CSS | 原子化样式，无 CSS 模块 |
| **后端** | Rust (stable) | 音频采集、STT/LLM 编排、文本输出、存储 |
| **音频采集** | cpal | 跨平台低延迟麦克风录音 |
| **STT 引擎** | Deepgram / AssemblyAI / Whisper / Groq 等 | 语音转文字（6+ 服务商） |
| **LLM 引擎** | OpenAI / DeepSeek / Claude / Gemini 等 | AI 文本润色（20+ 模型） |
| **文本输出** | enigo（键盘模拟）+ arboard（剪贴板） | 将润色后的文本输入到任意应用 |
| **配置存储** | tauri-plugin-store + SQLite (rusqlite) | 设置持久化 + 历史/词典存储 |
| **全局快捷键** | 原生 `WH_KEYBOARD_LL` + Tauri plugin | Windows 底层键盘钩子（Ctrl+Win）+ 常规快捷键 |
| **构建工具** | Vite + Cargo | 前端热更新 + Rust 编译 |

### 🔄 数据流 Pipeline

```
麦克风 → 音频采集 (cpal) → STT 服务商 (WebSocket/HTTP)
  → 原始转录文本 → LLM 润色 (OpenAI 兼容 API) → 键盘/剪贴板输出 (enigo/arboard)
                                                  ↗
                           流式分段: 实时 VAD 检测 → 独立分段 LLM + 输出
```

### 📂 项目结构

```
src/                  # React 前端（TypeScript + JSX）
├── components/       # UI 组件（Settings、History、Capsule、Onboarding）
├── hooks/            # React hooks（useTauriEvents、useTheme）
├── lib/              # 工具库（API 客户端、IPC 桥接、常量）
├── stores/           # Zustand 状态管理（appStore、authStore）
└── i18n/             # 国际化（en、zh）

src-tauri/src/        # Rust 后端
├── audio/            # 音频采集（cpal，下采样 + 单声道转换）
├── stt/              # STT 服务商（Deepgram、AssemblyAI、Whisper、Cloud）
├── llm/              # LLM 服务商（OpenAI 兼容、Cloud）+ prompt 系统
├── output/           # 文本输出（键盘模拟、剪贴板粘贴）
├── storage/          # 配置、历史记录、词典（SQLite WAL 模式）
├── app_detector/     # 检测当前活动应用（场景感知）
├── hotkey/           # Windows 底层键盘钩子（Ctrl+Win 检测）
├── pipeline.rs       # 状态机：录音 → STT → LLM → 输出
└── lib.rs            # Tauri 入口、IPC 命令、热键解析、系统托盘
```

### 🚀 构建与运行

```bash
# 安装依赖
npm install

# 开发模式（前端热更新 + Rust 后端）
npm run tauri dev

# 生产构建
npm run tauri build
```

### ⚙️ 配置说明

所有设置均可通过应用内设置面板访问：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `https://www.opentypeless.com` | 前端云 API 基础 URL |
| `API_BASE_URL` | `https://www.opentypeless.com` | Rust 后端云 API 基础 URL |

```bash
# 使用自定义后端构建
VITE_API_BASE_URL=https://my-server.example.com API_BASE_URL=https://my-server.example.com npm run tauri build
```

### ✅ 测试

```bash
# 前端测试
npm run test                 # Vitest（91 项测试）
npm run lint                 # ESLint

# Rust 测试
cd src-tauri && cargo test   # 41 项单元测试
cargo check                  # 快速编译验证
```

### 📜 许可证

[MIT](LICENSE) — 自由使用、修改和分发。

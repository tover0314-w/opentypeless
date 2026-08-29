<p align="center">
  <a href="README.md">English</a> | <a href="README_zh.md">中文</a> | <strong>日本語</strong> | <a href="README_ko.md">한국어</a> | <a href="README_es.md">Español</a> | <a href="README_fr.md">Français</a> | <a href="README_de.md">Deutsch</a> | <a href="README_pt.md">Português</a> | <a href="README_ru.md">Русский</a> | <a href="README_ar.md">العربية</a> | <a href="README_hi.md">हिन्दी</a> | <a href="README_it.md">Italiano</a> | <a href="README_tr.md">Türkçe</a> | <a href="README_vi.md">Tiếng Việt</a> | <a href="README_th.md">ภาษาไทย</a> | <a href="README_id.md">Bahasa Indonesia</a> | <a href="README_pl.md">Polski</a> | <a href="README_nl.md">Nederlands</a>
</p>

<p align="center">
  <img src="src-tauri/icons/128x128@2x.png" width="128" height="128" alt="OpenTypeless ロゴ" />
</p>

<h1 align="center">OpenTypeless</h1>

<p align="center">
  デスクトップ向けオープンソースAI音声入力。自然に話して、あらゆるアプリで洗練されたテキストを取得。
</p>

<p align="center">
  メールの作成、コーディング、チャット、メモの取得など — ホットキーを押すだけで、<br/>
  思ったことを話せば、OpenTypelessがAIで文字起こしとテキスト校正を行い、<br/>
  使用中のアプリに直接入力します。
</p>

<p align="center">
  <a href="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml"><img src="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/releases"><img src="https://img.shields.io/github/v/release/tover0314-w/opentypeless?color=2ABBA7" alt="リリース" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tover0314-w/opentypeless" alt="ライセンス" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/stargazers"><img src="https://img.shields.io/github/stars/tover0314-w/opentypeless?style=social" alt="スター" /></a>
  <a href="https://discord.gg/V6rRpJ4RGD"><img src="https://img.shields.io/badge/Discord-Join%20us-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

<p align="center">
  <img src="docs/images/v1.1.49-app-context-showcase.jpg" width="820" alt="Gmail、Slack、Google Docs、Cursor、Zendesk、LinkedIn に対応する OpenTypeless のアプリ認識音声入力" />
</p>

<p align="center">
  <img src="docs/images/voice-flow-demo.gif" width="720" alt="OpenTypeless デモ" />
</p>

## 主な機能

- **アプリ認識ライティング**は現在のアプリをローカルで検出し、メール、チャット、文書、Issue 管理、開発ツールなどに合わせて構成とトーンを調整します。
- **音声インテントルーティング**は、通常のディクテーション、選択テキスト編集、翻訳、Ask Anything、対応する音声操作を英語・簡体字中国語・繁体字中国語で判別します。
- **ワークフローごとの複数ショートカット**により、ディクテーション、Ask Anything、翻訳へ複数のキー設定を追加し、並べ替えできます。
- **切り替え可能な翻訳先**により、出力言語を一つに固定せず、普段使う言語をすばやく切り替えられます。
- **強化されたローカル辞書**は、修正ルールと辞書のインポート／エクスポートに対応します。
- **アプリ別スタイルマッピング**により、内蔵カテゴリを上書きし、特定アプリへ別の文章スタイルを割り当てられます。

アプリ検出、マッピング、辞書、修正ルールはすべてローカルに保存されます。アプリ認識の文章調整が設定済み LLM に送るのは、内部アプリカテゴリと承認済みスタイルメタデータだけです。生のウィンドウタイトルや文書内容をアプリコンテキストとして送信したり、履歴へ保存したりすることはありません。

| アプリ認識 AI 文章調整 | ローカル辞書と修正ルール |
| --- | --- |
| <img src="docs/images/v1.1.49-app-aware-polish.jpg" width="420" alt="OpenTypeless v1.1.49 アプリ認識 AI 文章調整" /> | <img src="docs/images/v1.1.49-dictionary.jpg" width="420" alt="OpenTypeless v1.1.49 ローカル辞書と修正ルール" /> |

<details>
<summary>その他のスクリーンショット</summary>

<p align="center">
  <img src="docs/images/app-main-light.png" width="720" alt="OpenTypeless メインウィンドウ" />
</p>

| 設定 | 履歴 |
|---|---|
| <img src="docs/images/app-settings.png" width="360" /> | <img src="docs/images/app-history.png" width="360" /> |

</details>

---

## なぜ OpenTypeless？

| | OpenTypeless | macOS Dictation | Windows Voice Typing | Whisper Desktop |
|---|---|---|---|---|
| AIテキスト校正 | ✅ 複数のLLM | ❌ | ❌ | ❌ |
| STTプロバイダー選択 | ✅ 6以上のプロバイダー | ❌ Appleのみ | ❌ Microsoftのみ | ❌ Whisperのみ |
| あらゆるアプリで動作 | ✅ | ✅ | ✅ | ❌ コピー＆ペースト |
| 翻訳モード | ✅ | ❌ | ❌ | ❌ |
| オープンソース | ✅ MIT | ❌ | ❌ | ✅ |
| クロスプラットフォーム | ✅ Win/Mac/Linux | ❌ Macのみ | ❌ Windowsのみ | ✅ |
| カスタム辞書 | ✅ | ❌ | ❌ | ❌ |
| セルフホスト可能 | ✅ BYOK | ❌ | ❌ | ✅ |

## 機能

- 🎙️ グローバルホットキー録音 — 長押しまたはトグルモード
- 💊 常に最前面に表示されるフローティングカプセルウィジェット
- 🗣️ 6以上のSTTプロバイダー: Deepgram、AssemblyAI、Whisper、Groq、GLM-ASR、SiliconFlow
- 🤖 複数のLLMによるテキスト校正: OpenAI、DeepSeek、Claude、Gemini、Ollamaなど
- ⚡ ストリーミング出力 — LLMが生成するとともにテキストが表示
- ⌨️ キーボードシミュレーションまたはクリップボード出力
- 📝 録音前にテキストを選択してLLMにコンテキストを提供
- 🌐 翻訳モード: ある言語で話し、別の言語で出力（20以上の言語）
- 📖 専門用語のためのカスタム辞書
- 🔍 アプリ検出によるフォーマット自動調整
- 📜 全文検索対応のローカル履歴
- 🌗 ダーク / ライト / システムテーマ
- 🚀 ログイン時自動起動

> [!TIP]
> **最高の体験のための推奨設定**
>
> | | プロバイダー | モデル |
> |---|---|---|
> | 🗣️ STT | Groq | `whisper-large-v3-turbo` |
> | 🤖 AI校正 | Google | `gemini-2.5-flash` |
>
> この組み合わせは、高速で正確な文字起こしと高品質なテキスト校正を実現します。どちらも寛大な無料枠を提供しています。

## ダウンロード

お使いのプラットフォーム向けの最新バージョンをダウンロード：

**[Releases からダウンロード](https://github.com/tover0314-w/opentypeless/releases)**

| プラットフォーム | ファイル |
|-----------------|---------|
| Windows | `.msi` インストーラー |
| macOS (Apple Silicon) | `.dmg` |
| macOS (Intel) | `.dmg` |
| Linux | `.AppImage` / `.deb` |

## 前提条件

- [Node.js](https://nodejs.org/) 20+
- [Rust](https://rustup.rs/)（stableツールチェーン）
- Tauri用のプラットフォーム固有の依存関係：[Tauri Prerequisites](https://v2.tauri.app/start/prerequisites/) を参照

## はじめに

```bash
# 依存関係のインストール
npm install

# 開発モードで実行
npm run tauri dev

# 本番用ビルド
npm run tauri build
```

ビルドされたアプリケーションは `src-tauri/target/release/bundle/` に出力されます。

## 設定

すべての設定はアプリ内の設定パネルからアクセスできます：

- **音声認識** — STTプロバイダーを選択し、APIキーを入力
- **AI校正** — LLMプロバイダー、モデル、APIキーを選択
- **一般** — ホットキー、出力モード、テーマ、自動起動
- **辞書** — より正確な文字起こしのためにカスタム用語を追加
- **シーン** — さまざまなユースケース向けのプロンプトテンプレート

APIキーは `tauri-plugin-store` を介してローカルに保存されます。キーがOpenTypelessサーバーに送信されることはありません — すべてのSTT/LLMリクエストは設定したプロバイダーに直接送信されます。

### Cloud（Pro）オプション

OpenTypelessは、自分のAPIキーが不要なマネージドSTTおよびLLMクォータを提供するオプションのProサブスクリプションも提供しています。これは完全にオプションです — アプリは自分のキーで完全に機能します。

[Proについて詳しく](https://www.opentypeless.com)

### BYOK（Bring Your Own Key）vs Cloud

| | BYOKモード | Cloud（Pro）モード |
|---|---|---|
| STT | 自分のAPIキー（Deepgram、AssemblyAIなど） | 管理されたクォータ（10時間/月） |
| LLM | 自分のAPIキー（OpenAI、DeepSeekなど） | 管理されたクォータ（約500万トークン/月） |
| クラウド依存 | なし — すべてのリクエストはプロバイダーに直接送信 | www.opentypeless.comへの接続が必要 |
| コスト | プロバイダーに直接支払い | $4.99/月のサブスクリプション |

すべてのコア機能 — 録音、文字起こし、AI校正、キーボード/クリップボード出力、辞書、履歴 — はBYOKモードでOpenTypelessサーバーから完全にオフラインで動作します。

### セルフホスティング / クラウドなし

クラウド依存なしでOpenTypelessを実行するには：

1. 設定でCloud以外のSTTおよびLLMプロバイダーを選択
2. 自分のAPIキーを入力
3. 以上です — www.opentypeless.comへのアカウントやインターネット接続は不要です

オプションのクラウド機能を自分のバックエンドに向けたい場合は、ビルド前にこれらの環境変数を設定してください：

| 変数 | デフォルト | 説明 |
|---|---|---|
| `VITE_API_BASE_URL` | `https://www.opentypeless.com` | フロントエンドクラウドAPIベースURL |
| `API_BASE_URL` | `https://www.opentypeless.com` | RustバックエンドクラウドAPIベースURL |

```bash
# 例：カスタムバックエンドでビルド
VITE_API_BASE_URL=https://my-server.example.com API_BASE_URL=https://my-server.example.com npm run tauri build
```

## アーキテクチャ

**データフローパイプライン：**

```
マイク → オーディオキャプチャ → STTプロバイダー → 生のトランスクリプト → LLM校正 → キーボード/クリップボード出力
```

```
src/                  # Reactフロントエンド（TypeScript）
├── components/       # UIコンポーネント（設定、履歴、カプセルなど）
├── hooks/            # Reactフック（録音、テーマ、Tauriイベント）
├── lib/              # ユーティリティ（APIクライアント、ルーター、定数）
└── stores/           # Zustand状態管理

src-tauri/src/        # Rustバックエンド
├── audio/            # cpalによるオーディオキャプチャ
├── stt/              # STTプロバイダー（Deepgram、AssemblyAI、Whisper互換、Cloud）
├── llm/              # LLMプロバイダー（OpenAI互換、Cloud）
├── output/           # テキスト出力（キーボードシミュレーション、クリップボード貼り付け）
├── storage/          # 設定（tauri-plugin-store）+ 履歴/辞書（SQLite）
├── app_detector/     # コンテキスト用のアクティブアプリケーション検出
├── pipeline.rs       # 録音 → STT → LLM → 出力オーケストレーション
└── lib.rs            # Tauriアプリセットアップ、コマンド、ホットキー処理
```

## ロードマップ

- [ ] カスタムSTT/LLM統合のためのプラグインシステム
- [ ] 多言語STT精度と方言サポートの改善
- [ ] 音声コマンド
- [ ] カスタマイズ可能なホットキー組み合わせ
- [ ] オンボーディング体験の改善
- [ ] モバイルコンパニオンアプリ

## よくある質問

**音声はクラウドに送信されますか？**
BYOKモードでは、音声は選択したSTTプロバイダー（Groq、Deepgramなど）に直接送信されます。OpenTypelessサーバーを経由することはありません。Cloud（Pro）モードでは、音声はマネージドプロキシに送信されて文字起こしが行われます。

**オフラインで使えますか？**
ローカルSTTプロバイダー（Ollama経由のWhisper）とローカルLLM（Ollama）を使用すれば、完全にオフラインで動作します。インターネット接続は不要です。

**どの言語がサポートされていますか？**
STTはプロバイダーによって99以上の言語をサポートしています。AI校正と翻訳は20以上のターゲット言語をサポートしています。

**アプリは無料ですか？**
はい。自分のAPIキー（BYOK）で全機能が利用できます。Cloud Proサブスクリプション（$4.99/月）はオプションです。

## コミュニティ

- 💬 [Discord](https://discord.gg/V6rRpJ4RGD) — チャット、ヘルプ、フィードバック
- 🗣️ [GitHub Discussions](https://github.com/tover0314-w/opentypeless/discussions) — 機能提案、Q&A
- 🐛 [Issue Tracker](https://github.com/tover0314-w/opentypeless/issues) — バグ報告と機能リクエスト
- 📖 [コントリビューションガイド](CONTRIBUTING.md) — 開発セットアップとガイドライン
- 🔒 [セキュリティポリシー](SECURITY.md) — 脆弱性の責任ある報告
- 🧭 [ビジョン](VISION.md) — プロジェクトの原則とロードマップの方向性

## コントリビューション

コントリビューションを歓迎します！開発セットアップとガイドラインについては [CONTRIBUTING.md](CONTRIBUTING.md) をご覧ください。

始める場所をお探しですか？ [`good first issue`](https://github.com/tover0314-w/opentypeless/labels/good%20first%20issue) ラベルの付いたissueをチェックしてください。

## Star History

<a href="https://star-history.com/#tover0314-w/opentypeless&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date" />
    <img alt="Star History チャート" src="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date" />
  </picture>
</a>

## Claude Code で1日で開発

このプロジェクト全体は [Claude Code](https://claude.com/claude-code) を使用して1日で構築されました — アーキテクチャ設計から完全な実装まで、Tauri バックエンド、React フロントエンド、CI/CD パイプライン、この README を含みます。

## ライセンス

[MIT](LICENSE)

# OpenTypeless × Typeless 2.9.0 UI 与功能对齐方案（v2，详细版）

- 分支：`codex/typeless-parity`（worktree `.worktrees/typeless-parity`，基于 `origin/main` `f5ad602`，OpenTypeless 1.1.64）
- 参照对象：Typeless macOS 2.9.0 (`now.typeless.desktop`, Electron 33.4.11)
- 状态：方案已按用户决策更新（v2），等待最终确认后开始 P0
- 日期：2026-11-01

## 0. 已确认的决策

| # | 决策 | 结论 |
|---|---|---|
| D1 | 颜色 | **保留 OpenTypeless 青绿 `#2abba7` 作为强调色**；布局、中性色、圆角、间距、层级按 Typeless |
| D2 | 设置保存 | 改为即时保存；服务商 / Key 保留"测试"按钮 |
| D3 | 口述键 | 同一个键自动识别按住（说话）与轻按（免提）；旧的按住 / 切换设置保留为兼容开关 |
| D4 | 录音留存 | 做；默认开启，跟随历史保留时长，可关闭 |
| D5 | 浮动条位置 | 默认底部居中；保留可拖动 |
| D6 | 多语言 | **全部新增和改动的文案必须覆盖现有 10 种界面语言**（en、zh、ja、ko、fr、de、es、pt、ru、it），见 §6 |
| D7 | 顺序 | P0 → P1 → P2 → P3；每阶段结束交付截图对比后再进入下一阶段 |
| 待定 | 真实口述截图 | 用 Typeless 真实口述 1–2 次，补齐浮动条录音态和答案卡截图（约消耗几十字免费额度） |

## 1. REA 流程执行记录

对照 `reverse-engineer-anything` 技能要求逐项记录。所有 Evidence 文件在 `/Users/bytedance/个人项目/rea/typeless-reference/rea-evidence/`（280MB，仓库外，不提交）。

### 1.1 已完成

| 步骤 | REA 命令 | Evidence ID | 结论 |
|---|---|---|---|
| 识别目标 | 手动查看 bundle | — | Typeless 是 Electron 应用，主体在 `app.asar`；按技能路由选 `analyze_javascript_application` |
| Electron fuse | `rea inspect-electron-fuses` | `ev_f01aed9f53b9…` | `EnableNodeCliInspectArguments` 开启，因此允许用 CDP 被动观察运行时 |
| 静态分析（原始 asar） | `rea analyze-javascript-application --detail summary --integrity-policy record-and-continue` | 父 `ev_fbb97ccc1f9c…`，摘要 `ev_05d323a21502…` | 主进程被 obfuscator.io 混淆，REA 只识别出 7 个字面 IPC 通道。better-sqlite3 / koffi 两个原生模块完整性不一致（重签名），已记录为 observed-untrusted |
| 去混淆后重新分析 | webcrack 去混淆 → `rea analyze-javascript-application --artifact-format directory` | 父 `ev_7279dc685a5b…`，摘要 `ev_c25e68471844…` | 识别出 **146 个主进程 handler、112 个字面 IPC 通道、1 个 contextBridge API（7 个成员）** |
| 复刻义务清单 | `rea build-reconstruction-obligation-ledger` | `ev_04857d64d7ed…`，ledger `rol_2af8223db806…` | **392 项义务**：IPC 292、协议端点 48、运行时 31、解析边界 13、配置 6、其他 2；27 个残留未知。全部状态为 candidate / unowned，因为还没有任何实现绑定 |
| 功能追踪 | `rea trace-application-feature`（seed kind `channel`） | `audio:ai-voice-flow` `ev_302d38f18065…`；`keyboard:type-transcript` `ev_816bd8f3f3d9…`；`page:open-interactive-card` `ev_0ab41035a513…`；`db:history-upsert` `ev_34bbeb33c3e1…`；`audio:mute` `ev_62f620548a60…` | 每个种子都唯一匹配到 IPC 节点；追踪覆盖为 partial（双向追踪会经包含关系扩散到 666 个节点，不代表这些节点都参与该功能） |
| 运行时目标 | `rea list-electron-targets` | `ev_6918607240bf…` | hub、floating-bar 两个页面 |
| 运行时被动观察 | `rea inspect-electron-page` | hub `ev_5a10aed27730…`（439 DOM 节点，15 个脚本）；floating-bar `ev_59c0b65a29c5…`（58 DOM 节点） | 脚本源码被策略过滤，DOM 与资源已记录 |
| 截图 | 本机 CDP（127.0.0.1:9333）+ `Page.captureScreenshot` | — | 12 张，见 `typeless-reference/screenshots/` |

### 1.2 没做或做不到的，以及原因

| 项 | 状态 | 原因 / 后续 |
|---|---|---|
| REA MCP 工具 | 未使用 | 当前会话未注册 REA MCP，全部用 REA CLI 6.8.0 完成，Evidence 格式相同 |
| 渲染层（UI 代码）进入 REA 图 | 未纳入 | 渲染层 78 个 chunk、7.6MB 主 chunk；UI 清单由子任务用 grep / 格式化代码直接整理（标注 Observed / Inferred），未进入 REA 图。后续可以用 `--source-path` 单独分析 hub、floating-bar chunk |
| `reconcile-javascript-runtime` | 未执行 | 运行时页面脚本源码被策略过滤，无法与静态图做脚本级对账。DOM / 截图已作为运行时证据保留 |
| 录音、答案卡运行时 | 未观察 | 需要真实口述，会消耗额度，等你同意（见 §0 待定） |
| 原生 helper（`libUtilHelper`、`libKeyboardHelper`、`libInputHelper`、`libContextHelper`） | 未反编译 | 只需要知道它们提供什么能力（静音、设备列表、按键监听、上下文读取），这些已从 koffi 绑定的函数签名得到。我们用 Rust 自己实现，不需要它们的实现细节 |
| `evaluate_reconstruction_coverage` / 义务绑定 | 待实现阶段做 | 每完成一个阶段，把 OpenTypeless 的实现与测试作为 manifest binding 写回 ledger，重新生成，跟踪 open 数下降 |

### 1.3 REA 关键事实（实现需要用到的）

- 三种模式与服务端模式名：`voice_transcript → transcript`，`voice_command → ask_anything`，`voice_translation → translation`（主进程 `Lc` 映射，`dist/main/index.js:17398` 去混淆版）。
- 语音请求 `POST /ai/voice_flow` 的表单字段：`audio_file, audio_id, audio_context, audio_metadata, device_name, is_retry, mode, parameters, send_time, user_over_time`。我们不调用它，只参考"一次请求带上音频 + 上下文 + 模式"的结构。
- 静音其他音频：原生 `muteAudio() / unmuteAudio() / isAudioMuted()`，返回 1 表示成功，-1 表示未知（`libUtilHelper`，koffi 绑定）。
- 设备列表：`getAudioDevicesJSON()` / `getAudioDevicesJSONAsync(cb)`；合盖检测 `deviceIsLidOpen()`（用于"更好的麦克风"推荐）。
- 文本插入：`keyboard:type-transcript → insertText`；渲染层还有 `insert-rich-text`。
- 答案卡：`page:open-interactive-card` 只在 payload 带 `answer` 时打开；高度由渲染层回报（`page:update-interactive-card-bounds`）。
- 历史：`db:history-upsert / -delete / -delete-by-duration / -save-audio`；本地 SQLite `history_v2` 表（字段见 `analysis/drizzle/0011…0013.sql`）。

## 2. 功能对照与差距（摘要）

逐项细节（每个控件的文案、尺寸、默认值）见 `typeless-reference/analysis/renderer-ui-inventory.md`（Typeless）和 `typeless-reference/analysis/opentypeless-inventory.md`（OpenTypeless，带 `path:line`）。图例：✅ 已有　🟡 有但差异大　❌ 缺失　➕ OpenTypeless 独有（必须保留）　⏭ 不做

| 区域 | Typeless | OpenTypeless | 结论 |
|---|---|---|---|
| 主窗口 | 1080×750，最小 988×685，隐藏标题栏 | 900×700，原生标题栏 | 🟡 P0 |
| 导航 | 首页 / 历史记录 / 词典；左下图标行进入账户 / 设置 / 帮助 | 首页 / 设置 / 历史 / 升级 / 账户 | 🟡 P0 |
| 设置 | 940×652 弹窗，5 个标签，即时保存 | 独立页面，6 个面板，Save / Reset | 🟡 P0 |
| 服务商 / BYOK | 无 | STT 11 家、LLM 13 家、Key、测试 | ➕ 新增"模型"标签 |
| 场景 / app 写作模式 / 纠错规则 | 无（个性化是服务端学习） | 有 | ➕ 放进"个性化"标签 |
| 首页统计 | 字数 / 节省时间 / WPM / 总时长 / 热力图 / 连续天数 | 总次数（上限 200）/ 今日次数 | ❌ P2 |
| 快捷键卡、热门用例 | 有 | 无 | ❌ P2 |
| 历史 | 全部 / 听写 / 随便问；保留时长；单条删除 / 重试 / 下载音频；查看答案 | 搜索、复制、创建纠错 | ❌ P2 |
| 词典 | 一级页面；所有 / 自动 / 手动；批量删除 | 设置子面板；词汇 / 纠错；导入导出更强 | 🟡 P0 移位、P2 补功能 |
| 浮动条 | 细条 / hover / 录音 / 免提 / Thinking / 模式光晕 / 30+ 提示气泡 | 圆形 logo / 录音条 / 处理 / 完成 / 错误 | 🟡 P1 |
| 快捷键交互 | 按住与轻按自动识别；粘贴上一条 ⌃⌘V | 按住 / 切换二选一 | 🟡 P1 |
| 音频 | 选择麦克风、交互声音、语音时静音其他音频 | 全无 | ❌ P1 |
| 答案卡 | 744px、Markdown、选中文本引用、来源标签 | 400×220 纯文本便签 | 🟡 P3 |
| 引导 | 进度条 + 面包屑 + 练习示例 | 8 步居中卡片，含 BYOK 配置 | 🟡 P3 |
| 推荐好友 / 联盟 / 礼品卡 / 移动应用 / 邀请码 / 问卷 / 团队 | 有 | 无 | ⏭ |

## 3. 设计系统规格（P0 落地的 token）

强调色保留青绿；其余按 Typeless 的层级重建，统一放在 `src/styles/globals.css` 的 `@theme`。

| Token | 浅色 | 深色 | 来源 |
|---|---|---|---|
| `--color-accent` / hover / pressed | `#2abba7` / `#22a08e` / `#1c8a7a` | `#3ccbb6` / `#55d6c3` / `#2abba7` | **保留 OpenTypeless** |
| `--color-accent-soft-10/30/50` | 青绿 10% / 30% / 50% | 20% / 40% / 60% | 对应 Typeless `accent.decorate1/2/3` |
| `--color-bg-main` | `#ffffff` | `#242121` | Typeless `background.main` |
| `--color-bg-top` | `#ffffff` | `#3c3b3b` | `background.topLayer`（卡片、弹窗） |
| `--color-bg-bottom` | `#f2f1f0` | `#111111` | `background.bottomLayer` |
| `--color-fill-thin` / `-tough` | `rgba(119,119,119,.05)` / `.10` | `.15` / `.20` | 侧栏面板、设置左栏 |
| `--color-fill-hover` / `-selected` | `.07` / `.15` | `.30` / `.20` | 导航 hover / 选中 |
| `--color-text-primary/secondary/tertiary/disabled` | `rgba(17,17,17, 1/.75/.55/.35)` | `rgba(242,241,240, 1/.75/.55/.35)` | |
| `--color-action` / hover | `#1d1a1a` / `#3c3b3b` | `#f2f1f0` / `#e3e2e2` | **主按钮近黑**（"允许""新词"）；升级等品牌按钮用青绿 |
| `--color-border-thin` / `-thick` | `rgba(119,119,119,.07)` / `.15` | `.15` / `rgba(242,241,240,.1)` | |
| 成功 / 警告 / 错误 | `#008770` / `#ea4d00` / `#ec0f38` | `#00957c` / `#ff5b00` / `#ff525c` | |
| 圆角 | 8（控件）/ 12（弹窗）/ 16（小卡）/ 20（大卡、侧栏）/ 999（胶囊） | | |
| 字体 | 正文系统字体 14/20；页面标题 40–48 粗体；分区标题 16/500 tertiary；设置行标题 14/500 + 描述 14 tertiary | | 不打包 Prompt / SF Pro 字体文件 |
| 布局常量 | 侧栏 210（面板 202 + 8 外边距）；标题栏 44；内容最大宽 1080；首页右栏 186；设置弹窗 940×652、左栏 202 | | |
| 胶囊 | 空闲 40×6 灰色 50%；hover / 录音 70×34；免提 116×34；处理 92（联网 170）×34 | | |

删除：`.jelly-*` 系列渐变类、`jelly-wobble` 等动画、`framer-motion` 的 jelly 弹簧（保留普通淡入、布局过渡）。

## 4. 分阶段实施清单

每个任务都写明：改哪些文件、做什么、验收标准。每个阶段统一执行验证：

```bash
npx tsc --noEmit && npx eslint src/ && npx prettier --check src/ && npx vitest run
cargo fmt --check --manifest-path src-tauri/Cargo.toml && cargo clippy --manifest-path src-tauri/Cargo.toml -- -D warnings && cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri dev   # 逐页截图，与 typeless-reference/screenshots 对照；浅色 + 深色；中文 + 英文
```

### P0 外壳与设计系统（预计改动约 30 个文件，不改业务逻辑）

| # | 任务 | 文件 | 验收 |
|---|---|---|---|
| P0-1 | 设计 token 重建（§3），删除 jelly 类；深色模式跟随 `config.theme` | `src/styles/globals.css`、`src/hooks/useTheme.ts`、`src/lib/animations.ts` | 浅 / 深色两套截图；无 `jelly-` 类残留（`rg jelly- src/` 为空） |
| P0-2 | 主窗口尺寸与隐藏标题栏：1080×750、最小 988×685；macOS `titleBarStyle: "Overlay"`、`hiddenTitle: true`；交通灯留位；Windows / Linux 保留原生边框 | `src-tauri/tauri.conf.json`、`src-tauri/src/window_state.rs`（最小尺寸校验 720×480 → 988×685） | 三平台窗口可拖动、可缩放；旧的窗口记忆数据小于新最小值时回落默认 |
| P0-3 | 路由改为 `home / history / dictionary`；`settings`、`account`、`upgrade` 改为弹窗状态（兼容旧 hash：`#/settings?pane=x` 打开设置弹窗对应标签） | `src/lib/router.ts`、`src/App.tsx`、`src/hooks/useTauriEvents.ts`（托盘 `tray:settings / tray:about` 打开弹窗对应标签，修复 about 打开错误面板的旧问题） | 所有旧深链和托盘入口都能到达正确位置 |
| P0-4 | 侧栏重做：Logo + 套餐徽章（Free / Pro / Lifetime / BYOK）、3 个导航项、底部用量卡（cloud words 进度；BYOK 显示"自带密钥"状态）、底部图标行（账户 / 设置 / 帮助）；拖动区域 | `src/components/MainLayout/index.tsx`、新增 `MainLayout/UsageCard.tsx`、`MainLayout/SidebarFooter.tsx` | 与 `11-home.png` 布局对齐；横幅（辅助功能、云服务故障）保留在内容区顶部 |
| P0-5 | 设置弹窗外壳：940×652、左栏 202、关闭按钮、Esc 关闭；标签：**账户 / 设置 / 模型 / 个性化 / 关于 / 帮助** | 新增 `src/components/SettingsModal/`（`index.tsx`、`SettingsMenu.tsx`、`SettingsSection.tsx`、`SettingsRow.tsx`）；替换 `src/components/Settings/index.tsx`、`SettingsSidebar.tsx` | 与 `20-settings-general.png` 对齐；键盘可完整操作 |
| P0-6 | 设置 › 设置：快捷键（口述 / 翻译 / 随便问，沿用 `ShortcutBindingList`，改为"添加另一个"样式）、语言（界面语言从关于页移入；翻译目标从 AI 润色页移入，保留最多 5 个；识别语言 `stt_language`）、音频（P1 填充，P0 先放占位分区）、常规（外观 `theme`、登录启动、最小化启动、Dock 显示〈macOS〉、关闭时到托盘 `close_to_tray`、空闲隐藏浮动条）、输出（输出方式、粘贴快捷键 `paste_shortcut`、粘贴后恢复剪贴板、流式插入） | `GeneralPane.tsx` 拆分改写；`TranslationTargets.tsx`、`ShortcutBindingList.tsx` 换样式 | 原有 General 面板全部选项仍在；新增 6 个原先无 UI 的配置可修改并持久化 |
| P0-7 | 即时保存：移除 DirtyBar；每次修改调用 `update_config`；热键注册失败时回滚并提示（复用现有回滚逻辑） | `shared/useDirtyConfig.ts` → 新 `shared/useConfigField.ts`；删除 `shared/DirtyBar.tsx`；`src/stores/appStore.ts` | 修改立即生效；失败回滚有 toast；相关测试改写 |
| P0-8 | 设置 › 模型（➕）：语音识别、AI 润色两个分区，承接 `SttPane`、`LlmPane` 全部内容（服务商、Key、测试、模型、Base URL、录音上限、润色开关、润色风格、自定义指令、选中文本上下文） | `SttPane.tsx`、`LlmPane.tsx` 改为分区组件 | 原有 SttPane / LlmPane 测试全部通过（按新 DOM 调整查询） |
| P0-9 | 设置 › 个性化（➕）：承接"按 app 写作模式"、场景列表、上下文自适应开关、最近一次识别的 app | `ScenesPane.tsx`、`ManageAppMappingsDialog.tsx`、`AppStyleMappingDialog.tsx`、`ContextAdaptationApps.tsx` | 场景导入导出、映射对话框全部可用 |
| P0-10 | 设置 › 账户：承接 `AccountPage` 全部内容（登录 / 注册 / OAuth / 密码 / 订阅 / 云备份 / 支付管理 / 退出），改为行式布局；升级改为从账户和侧栏用量卡打开的弹窗 | `src/components/AccountPage/index.tsx`、`src/components/UpgradePage/index.tsx` | 账户、升级测试通过；Stripe / Creem / Lifetime / AppSumo 流程不变 |
| P0-11 | 设置 › 关于：版本、检查更新（把 `UpdatePrompt` 的逻辑做成 关于页 + 更新弹窗）、开源许可、GitHub、隐私 / 条款链接 | `AboutPane.tsx`、`src/components/UpdatePrompt.tsx` | 更新状态：检查中 / 有更新 / 下载中 / 已下载 / 已是最新 / 出错 |
| P0-12 | 设置 › 帮助：帮助中心、版本说明（GitHub Releases）、反馈（GitHub Issues / Discord）；"探索功能"在 P3 接入 | 新增 `SettingsModal/HelpTab.tsx` | 链接用系统浏览器打开 |
| P0-13 | 词典提为一级页面：标题 + 新词按钮 + 搜索；分段 **词汇 / 纠错规则**；导入导出放到右上 "…" 菜单 | `src/components/Settings/DictionaryPane.tsx` → `src/components/Dictionary/index.tsx` | 原 DictionaryPane 测试全部迁移通过 |
| P0-14 | 历史、首页先换外观（新 token、标题样式），功能不变 | `History/index.tsx`、`HomePage/index.tsx` | 视觉对齐，无功能回退 |
| P0-15 | 多语言：所有新增文案在 10 种语言补齐（§6）；托盘标签同步 | `src/i18n/locales/*.json`、`src-tauri/src/tray.rs` | `localeParity.test.ts` 通过；新增"无硬编码文案"检查（§6.3） |
| P0-16 | REA ledger 回写：为本阶段覆盖的 Typeless 义务（`page:open-hub`、`page:change-hub-route`、`page:open-settings-modal`、`store:use`、`i18n:*`、`permission:update-auto-launch`、`permission:update-show-app-in-dock` 等）写 manifest binding，重新生成 ledger | `typeless-reference/rea-evidence/manifest-p0.json`（仓库外） | ledger 对应项从 unowned 变为 implemented / out-of-scope |

### P1 浮动条、快捷键、音频

| # | 任务 | 文件 | 验收 |
|---|---|---|---|
| P1-1 | 胶囊状态模型重做：`idle`（40×6 细条，`capsule_auto_hide` 时完全隐藏）→ `hover`（70×34 + 点阵，提示"点击开始口述"）→ `starting`（54×12 微光）→ `recording`（70×34 白色细波形：2px 宽、2px 间距、高 2–18px）→ `hands_free`（116×34：取消 × / 波形 / 完成 ✓）→ `processing`（92×34 "Thinking" + 假进度：1s 80%、2s 90%、3s 95%…完成跳 100%）→ `done` → `error` | `src/components/Capsule/*`、`src/hooks/useCapsuleResize.ts`、`src/stores/appStore.ts`（新增 `hands_free` 视图状态） | 每个状态截图；状态切换动画 0.2s；拖动保留 |
| P1-2 | 模式光晕：翻译、随便问在胶囊顶部加不同色光晕（青绿系两种色调，不用 Typeless 的蓝 / 绿原色）；翻译目标改为光晕旁小标签 + 右键菜单切换 | `Capsule/index.tsx`、`TranslateTargetChip.tsx`、`CapsuleContextMenu.tsx` | 三种模式一眼可分 |
| P1-3 | 按住 / 轻按自动识别：按下 ≥ 250ms 后松开 = 按住说话结束；< 250ms 松开 = 进入免提，再按一次结束；免提时 Esc 取消。旧 `hotkeys.dictationMode` 保留为"始终按住 / 始终切换 / 自动（默认）" | `src-tauri/src/hotkey.rs`（`HotkeyModeCache` 增加 `Auto`）、`src-tauri/src/native_hotkey.rs`（Fn / RightAlt 按下时间戳）、`src-tauri/src/storage/mod.rs`（迁移：新安装默认 `auto`；已有用户保留原值） | Rust 单测：短按、长按、免提中再按、免提中 Esc、录音启动中再按 |
| P1-4 | 粘贴上一条快捷键：macOS ⌃⌘V，Windows / Linux `Ctrl+Shift+Alt+V`（避开 Typeless 的 Windows 默认，减少冲突），可在设置 › 快捷键修改 | `hotkey.rs`（新 role `PasteLast`）、`storage/mod.rs`（`hotkeys.pasteLastBindings`）、`output/mod.rs`、`ShortcutBindingList.tsx` | 历史为空时提示；用最近一条成功的历史文本 |
| P1-5 | 麦克风选择：cpal 枚举输入设备（名称、是否默认、类型：内置 / 蓝牙 / USB 推断）；配置 `microphone_device`（空 = 自动 / 系统默认）；设备不存在时回落默认并提示；设置 › 音频 弹窗（列表 + 实时电平条 + 保存）；托盘"选择麦克风"子菜单 | `src-tauri/src/audio/capture.rs`（`default_input_device()` 改为按名称选择）、`audio/mod.rs`（`list_input_devices`）、新 `commands/audio.rs`、`tray.rs`、新 `SettingsModal/MicrophoneDialog.tsx` | 切换设备后下一次录音生效；拔掉设备回落默认 |
| P1-6 | 交互声音：自制开始 / 结束 / 错误 3 个短音效（自己合成，不用 Typeless 的 wav），配置 `interaction_sounds`（默认开）；在胶囊窗口用 Web Audio 播放，避免 Rust 引入音频播放依赖 | `src/assets/sounds/`、`src/components/Capsule/useInteractionSounds.ts`、`storage/mod.rs` | 关闭后无声；音量适中 |
| P1-7 | 录音时静音其他音频：配置 `mute_background_audio`（默认开）；macOS 用 CoreAudio 设置默认输出设备 mute 属性，录音结束恢复（只恢复我们改过的状态）；Windows 用 `IAudioEndpointVolume::SetMute`；Linux 暂不支持，设置项隐藏 | 新 `src-tauri/src/audio/output_mute.rs`（`#[cfg(target_os)]` 分平台）、`pipeline.rs`（开始 / 结束 / 异常 / 取消路径都要恢复） | 崩溃保护：启动时检测上次未恢复的静音并恢复；单测覆盖状态机 |
| P1-8 | 浮动条提示气泡系统：气泡在胶囊上方，最大宽 360，深色底，自动消失 2s / 5s / 10s / 常驻；首批 12 个：按键教学（首次）、按住 / 轻按结束提示、切换到免提、翻译目标提示、无翻译目标、网络慢 / 无网络、麦克风不可用 / 无权限、辅助功能权限、录音即将到上限（最后 1 分钟倒计时）、已取消（可撤销 → 打开历史）、复制上一条、更新可用 | 新 `src/components/Capsule/CapsuleAlert.tsx`、`useCapsuleAlerts.ts`；`useCapsuleResize.ts` 增加气泡尺寸；`pipeline.rs` 发出 `capsule:alert` 事件 | 每个气泡 10 种语言；不遮挡点击（只有气泡和胶囊可点） |
| P1-9 | 托盘菜单对齐：打开首页 / 显示历史 / 添加词汇 / 设置… / 选择麦克风 ▸ / 版本 / 检查更新 / 退出；保留"开始录音""空闲隐藏胶囊" | `src-tauri/src/tray.rs` | 10 种语言标签 |
| P1-10 | REA ledger 回写：`audio:mute / unmute / is-muted`、`audio:get-devices-async`、`keyboard:*`、`page:floating-bar-*`、`recording-machine:*` | 仓库外 manifest | |

### P2 首页、历史、词典

| # | 任务 | 文件 | 验收 |
|---|---|---|---|
| P2-1 | 历史表扩展（追加式迁移）：`mode`（dictate / translate / ask）、`question_text`、`answer_text`、`word_count`、`audio_path`、`audio_duration_ms`、`deleted_at`（可选） | `src-tauri/src/storage/mod.rs`（`:1700-1747` 迁移段）、`pipeline.rs::save_history`、`commands/ask.rs`（Ask 结果写历史） | 旧库升级无损；新字段有默认值 |
| P2-2 | 统计查询：口述字数（中日韩按字符、其他按空格分词）、总口述时长、WPM、节省时间（按 40 WPM 打字基线估算，文案注明是估算）、活跃天数 / 当前连续 / 最长连续、按天聚合（热力图） | 新 `commands/stats.rs`、`storage/stats.rs` | 单测覆盖跨时区日期边界、空数据 |
| P2-3 | 首页：大标题；快捷键卡（口述 / 翻译 / 随便问 + 键帽，读当前配置）；四项统计卡；活跃热力图（自绘 DOM，不引入 echarts）；热门用例卡（复用 `src/assets/app-icons`，点击用浏览器打开对应网页）；右栏版本 + 检查更新；保留"当前配置"信息并改为可读服务商名称 | `src/components/HomePage/`（拆为 `ShortcutsCard.tsx`、`StatsGrid.tsx`、`ActivityHeatmap.tsx`、`UseCaseGrid.tsx`） | 与 `11-home.png` 对齐；数据 0 时有占位 |
| P2-4 | 历史页：标签 全部 / 听写 / 随便问（+ 翻译）；顶部保留时长下拉（永远 / 1 年 / 1 月 / 1 周 / 24 小时 / 从不，缩短二次确认，接 `history_retention_days` / `history_enabled`）；隐私说明卡；"…" 菜单：删除全部、云备份 / 恢复（➕，从账户页复制入口）；单条：复制、删除、重试（有音频时）、下载音频、创建纠错规则（➕）；随便问条目"查看答案" | `src/components/History/index.tsx` 拆分；新 `commands/history.rs` 增加 `delete_history_entry`、`retry_history_entry`、`export_history_audio` | 与 `12-history.png` 对齐 |
| P2-5 | 录音留存：配置 `keep_audio`（默认开）；录音结束写 `app_data/recordings/<id>.wav`（后续可换 Opus）；保留时长清理同时删文件；设置 › 常规显示占用空间与"打开文件夹" | `pipeline.rs`、`storage/mod.rs`、新 `storage/recordings.rs` | 删除历史时文件同步删除；磁盘满时不影响主流程 |
| P2-6 | 重试：用留存音频重新走 STT + 润色，结果替换该条历史（不自动插入，提供复制） | `pipeline.rs`（新 `retry_from_audio`）、`commands/history.rs` | 无音频时按钮隐藏 |
| P2-7 | 词典：标签 所有 / 自动添加 / 手动添加 + 纠错规则分段；新增弹窗（≤ 100 字符，保留现有上限）；多选、全选、批量删除；词条来源字段 `source`（manual / auto / import） | `src/components/Dictionary/`、`storage/mod.rs`（dictionary 表加 `source`、`created_at`） | 与 `13-dictionary.png` 对齐 |
| P2-8 | 词典进入识别：对支持关键词的 STT（Deepgram keywords、AssemblyAI word_boost、Volcengine 热词、Whisper prompt）传入词典；纠错规则在润色前做确定性替换（可选开关，默认关，避免误替换） | `src-tauri/src/stt/mod.rs`（`SttConfig` 加 `vocabulary`）、各 provider、`pipeline.rs` | 每个 provider 单测请求体 |
| P2-9 | REA ledger 回写：`db:history-*`、`file:save-audio-with-dialog`、`file:open-recordings`、`file:read-recordings-size`、`file:pick-and-parse-dictionary-csv` | 仓库外 manifest | |

### P3 随便问答案卡、引导、帮助

| # | 任务 | 文件 | 验收 |
|---|---|---|---|
| P3-1 | 答案卡重做：744px 宽（小屏自适应），高 400–700 随内容；Logo + 关闭；问题块（含选中文本引用，2 行折叠"…更多"）；"答案"块 Markdown 渲染（标题、列表、代码、表格，链接用浏览器打开）+ 复制；Esc / 关闭 / 失焦关闭；窗口高度由前端测量后通知 Rust | `src/components/AskPanel/AskPanel.tsx`、`src-tauri/tauri.conf.json`（ask 窗口尺寸）、`src-tauri/src/lib.rs::ensure_ask_window`；新增 Markdown 依赖（`react-markdown` + `remark-gfm`，不引入 KaTeX） | 与静态证据尺寸一致；XSS 安全（禁用原始 HTML） |
| P3-2 | 选中文本上下文默认开启（新安装）；首次选中文本时浮动条气泡提示"按 X 对选中文字提问" | `storage/mod.rs`（`new_install_default`）、P1-8 气泡 | 老用户不改 |
| P3-3 | 引导重做外框：顶部 4px 进度条 + 面包屑 "开始 › 设置 › 体验"；左侧内容、右侧示意图（自己绘制，不用 Typeless 素材）；步骤：欢迎（界面语言）→ 登录（可跳过）→ 云端或自带密钥（➕）→ STT / LLM 配置与测试（➕，云端跳过）→ 权限（麦克风 / 辅助功能 / 浏览器自动化，带隐私说明）→ 麦克风测试（电平条）→ 快捷键测试（按下时按钮变色）→ 口述练习（示例文本 + 内嵌文本框）→ 翻译练习 → 随便问练习 → 完成 | `src/components/Onboarding/*` | 每步可后退；进度持久化；已完成用户不会再看到 |
| P3-4 | 设置 › 帮助 › "探索功能"：重新进入练习步骤（不重置配置） | `HelpTab.tsx`、`Onboarding/index.tsx`（`mode: 'explore'`） | |
| P3-5 | 可选：分享洞察图（本地生成 PNG，复制 / 保存） | `HomePage/ShareInsightsDialog.tsx` | |
| P3-6 | REA ledger 回写：`page:*-interactive-card`、`page:complete-onboarding`、`permission:request`、`permission:diagnose-microphone` | 仓库外 manifest | |

### P4 可选

- 自动学习词典：插入后监听目标文本框的修改（macOS AX API 读值），差异中出现的新专有名词加入词典并气泡提示可撤销。跨平台难度高，单独立项。
- 界面语言从 10 种扩展（例如繁中、越南语、泰语、印尼语、土耳其语、波兰语、荷兰语、阿拉伯语、印地语）。仓库 README 已有这些语言版本，可复用术语。

### 明确不做

推荐好友、联盟计划、礼品卡、移动应用推广、邀请码、获客问卷、团队 / 企业管理、Typeless 的历史云同步协议、服务端"个性化学习比例"（我们没有对应数据，不伪造指标）。

## 5. 保留清单（每阶段回归必查）

1. 11 个 STT 服务商与 13 个 LLM 服务商的配置、Key（系统钥匙串）、测试、模型列表获取
2. 云端模式：登录（邮箱 / Google / GitHub）、订阅、cloud words、Pro / Lifetime / AppSumo、Stripe 与 Creem 迁移、支付管理、云备份与恢复
3. 场景：系统场景覆盖、自定义场景、导入导出、按 app 家族分配
4. 按 app 写作模式：73 个 app 档案、用户映射、浏览器域名识别与权限提示
5. 语音意图路由（中英繁三语）：改写选区、翻译选区、提问选区、搜索、草稿
6. 翻译：最多 5 个目标、录音中切换
7. 纠错规则、词典导入导出（txt / csv / json）
8. 输出：键盘模拟 / 剪贴板粘贴 / 仅复制 / Windows SendInput、恢复剪贴板、Wayland 降级
9. 快捷键：每个角色最多 3 个、Fn / RightAlt 原生监听、冲突检测、注册失败自动恢复
10. 跨平台：macOS / Windows / Linux（含 Wayland 提示与 WebKit 兼容开关）
11. 自动更新、自启动、单实例、深链 `opentypeless://`
12. 录音时长上限按服务商能力

## 6. 多语言要求

### 6.1 现状

- 10 种界面语言：en、zh、ja、ko、fr、de、es、pt、ru、it；每种 790 个叶子键，20 个顶层命名空间。
- `src/i18n/__tests__/localeParity.test.ts` 保证所有语言与英文键完全一致。
- 托盘文案在 Rust 侧 `src-tauri/src/tray.rs` 单独维护 10 种语言。
- 胶囊空闲态、Logo 组件不含文案；其余胶囊组件都用 `useTranslation`。

### 6.2 规则

1. 新增文案先写英文和简体中文，再补其余 8 种；**同一个 PR 内补齐**，不允许留英文占位。
2. 键名按新的信息架构组织：`shell.*`（侧栏、用量卡）、`settingsModal.*`（标签、分区、行）、`capsule.alert.*`（气泡）、`home.stats.* / home.activity.* / home.useCases.*`、`history.filters.* / history.retention.*`、`dictionary.*`、`audio.*`、`onboarding.v2.*`。
3. 旧键：页面重做后不再使用的键在同一阶段删除，parity 测试会同步约束。
4. 不照搬 Typeless 原文：参考 `typeless-locale-en-zh.tsv` 只用来对齐**信息量和语气**（简短、口语、第二人称），文字自己写。
5. 复数和数字：统计卡用 i18next 复数（`_one` / `_other`）和 `Intl.NumberFormat` / `Intl.RelativeTimeFormat`；日期分组（今天 / 昨天 / 日期）用 `Intl.DateTimeFormat`，跟随界面语言。
6. 键帽显示：`Fn`、`Space`、`Left Shift`、`⌘`、`⌃` 等按键名在各语言中保持英文或系统符号（与 Typeless 一致），但说明文字翻译。
7. 布局适配长文案：德语、俄语、法语最长；设置行右侧控件最小宽 200，标题允许换行；胶囊气泡最大宽 360 自动换行。
8. Rust 侧发给前端的错误和提示只发**错误码 + 参数**，由前端翻译（现有 `errors.*` 机制）；托盘、原生菜单仍在 `tray.rs` 维护 10 种语言。

### 6.3 新增检查

- 保留 `localeParity.test.ts`。
- 新增 `src/i18n/__tests__/noHardcodedCopy.test.ts`：扫描 `src/components/**/*.tsx` 中 JSX 文本节点和 `aria-label` / `title` / `placeholder` 字面量，允许白名单（品牌名、按键名、数字）。`src/App.tsx` 里现有的 "Loading..." / "Failed to load application data." / "Retry" 一并改为翻译键。
- 新增 Rust 单测：`tray.rs` 每种语言的每个新增菜单项都有非空标签。
- 每阶段截图包含 **中文 + 英文 + 德语**（最长文案）三套。

## 7. 测试与验证策略

- 先写测试再改实现（TDD）：每个 P 任务先改 / 加测试描述新行为，再实现。
- 前端现有 51 个测试文件：DOM 结构大改的（`Settings.test.tsx`、`MainLayout.test.tsx`、`History.test.tsx`、`DictionaryPane.test.tsx`、`Onboarding.test.tsx`、`Capsule*`）按新结构改写，断言行为而不是类名。
- `src/lib/__tests__/releaseVersion.test.ts` 这类读源码的测试保持通过。
- Rust 新增：热键按住 / 轻按状态机、静音恢复状态机、麦克风选择回落、历史迁移、统计聚合、录音文件清理。
- 视觉验证：`npm run tauri dev`，每页浅色 / 深色、中 / 英 / 德截图，与 `typeless-reference/screenshots` 并排对比，放到仓库外 `typeless-reference/parity-shots/P0/…`。
- 不做的验证要明说：Windows / Linux 实机在本机无法验证，会在每阶段报告中标注。

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| P0 改动面大，测试大量失败 | 按任务小步提交；每个任务结束跑完整前端测试 |
| 与其他 20+ 个 worktree 冲突（账户、升级、胶囊、热键） | 每阶段开始前 `git fetch && git rebase origin/main`；冲突以 main 的业务逻辑为准，本分支只改布局 |
| macOS 隐藏标题栏后拖动区域失效 | 显式 `data-tauri-drag-region`；交通灯位置留空 |
| 静音其他音频后崩溃导致系统一直静音 | 只恢复自己改过的状态；启动时检测残留标记并恢复 |
| 按住 / 轻按识别误判 | 阈值 250ms 可配置；保留"始终按住 / 始终切换"兼容开关 |
| 录音文件占磁盘 | 跟随保留时长清理；显示占用空间；可关闭 |
| 品牌 / 法律 | 只对齐交互与信息架构；不使用 Typeless 名称、图标、插画、音效、字体文件和原文文案；参考资料不进仓库 |

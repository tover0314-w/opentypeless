# OpenTypeless BYOK + Android Voice Studio

[English](README.md)

这是一个独立维护的 MIT fork：已去掉 OpenTypeless 的账户、订阅、结账、额度、捐助入口和托管云运行链路，保留桌面端 BYOK 能力，并新增 OpenTypeless Android 0.2。它不是把“ASR + LLM”强行用于所有输入，而是一套本地优先、可验证、可撤回的系统级语音输入层。

- Fork：[dengxuezhao/opentypeless](https://github.com/dengxuezhao/opentypeless)
- 上游项目：[tover0314-w/opentypeless](https://github.com/tover0314-w/opentypeless)

## Android 0.2 核心能力

- **三个原生入口：**独立语音 IME、标准 `RecognitionService`、`RecognizerIntent` Activity。兼容的完整键盘可以继续提供字母、滑行输入、Emoji 和剪贴板，只把语音识别交给 OpenTypeless。
- **本地优先：**首次安装时，只有系统确认设备端识别真正可用，才默认选择 Android 设备端；否则依次选择系统语音服务或用户明确配置的 BYOK/自建 OpenAI 兼容端点。系统语音服务是否联网由其提供方决定，界面不会把它冒充为离线。
- **经过实测的可选离线模型：**非低内存设备可主动下载固定版本的 228.45 MiB SenseVoice Small INT8 质量模型，保存在不备份的 App 私有目录。安装前和首次识别前都会核对大小与 SHA-256，可随时在设置中删除，APK 本身不内置权重。
- **AI 可选：**精确模式和结构化字段不需要 LLM。智能整理、翻译和选中文字编辑，只有用户开启 OpenAI 兼容 LLM 后才运行。
- **专名真正进入识别链：**用户确认的标准写法、读音、常见错词、纠正规则和 App 作用域，会在后端支持时进入 ASR prompt、Android biasing strings，并在识别后执行一次性、非级联的确定性纠正。
- **词汇可迁移：**Android 既能导入早期 Android 个性化备份，也能导入桌面端 `opentypeless_dictionary` v1；Android 导出的兼容超集可由桌面端读取，同时为 Android 间往返保留别名、App 作用域和启用状态。
- **只显式学习：**不会偷偷学习用户全部键盘输入。只有用户在词典页添加，或点击 **Teach** 并确认最小的“错词 → 正词”片段后，才会保存规则。
- **事实保护与可逆 AI：**AI 输出会复核数字、金额、日期、网址、邮箱、代码形态 token、否定词和个人词。风险输出会被拦截并回退精确转写；插入后可安全 Undo，AI 听写还可一键恢复 Raw 原始转写。
- **结果严格绑定输入目标：**每次录音都绑定 editor epoch、App、field、`InputConnection`、选区及光标前后文本指纹。切 App、切输入框、进入密码框、移动光标或改变选区后，旧结果不会写入新位置。
- **本地隐私：**API Key 与可选历史正文分别使用 Android Keystore 不可导出的 AES-GCM 密钥；历史默认关闭，支持逐条删除/全部清空，有本地数量上限，也不会进入词典导出文件。
- **按 App 配置：**可显式设置每个 App 的 Auto/Exact/Smart/Translate 模式、翻译目标、写作偏好，以及是否允许发送有限的光标前上下文。
- **语音体验：**轻触空格仍输入空格，按住空格即可说话、松手结束；Android 后端使用原生 partial result，本地 SenseVoice 每 0.75 秒重识别有界前缀并在编辑框中原位修订。最终结果会再走完整识别、个人规则和“只可改标点、不可改词或数字”的安全门。上传型后端支持静音自动停录、开头静音裁剪、持久取消令牌和录音上限。

APK 不内置任何语音或语言模型。许可证说明见 [Android 第三方声明](android/THIRD_PARTY_NOTICES.md)。首个 189.85 MiB Zipformer 已被否决；第二轮在完整 1,315 条 ASCEND test 上测试了 SenseVoice 与 Paraformer。SenseVoice 达到普通话 CER 11.4%、英文 WER 25.9%、中英混说 MER 13.3%，并通过 API 36 arm64 的真实下载与原生识别烟测。现在使用经校验的 ASR-only 双 ABI 运行时，干净构建的通用 debug APK 已从上游全功能包的约 120 MiB 降到 52.54 MiB；但约 457 MiB 瞬时峰值仍使它不适合直接称为全设备默认。同体积档的 Paraformer Large 与 Whisper Small Q5_1 也已实测并被否决为中英默认。用户明确设置 `zh-*` 或 `cmn-*` 时，现在会启用 SenseVoice 普通话锁定；固定 A/B 将公开集普通话 CER 从 10.59% 降至 10.01%、混说 MER 从 20.37% 降至 18.31%。英文保持自动检测，因为强制 `en` 反而退化。详见
[第二轮离线模型评测](docs/2026-08-09-offline-asr-candidate-round-2.md)和
[可复现测试工具](benchmarks/offline_asr/README.md)。

## 场景策略

| 场景 | Auto 行为 | 生成式 AI |
| --- | --- | --- |
| 密码/敏感字段 | 禁用语音 | 永不调用 |
| URL、邮箱、数字、人名、搜索 | 精确转写 + 已确认本地规则 | 跳过 |
| 消息、长文本、普通正文 | 开启后进行保守智能整理；失败回退精确转写 | 可选 |
| 选中文字 | 语音明确编辑，并在写入前复核选区 | 必需；失败保留原文 |
| 翻译 | 忠实翻译到配置目标 | 必需；失败不会插入语音指令 |

遇到 `IME_FLAG_NO_PERSONALIZED_LEARNING` 时，OpenTypeless 不采集上下文、不写历史、不增加使用学习计数；已有且用户确认过的词典仍可辅助当前识别，但不会被修改。

## 隐私与网络边界

- “Android 设备端”只通过 `SpeechRecognizer.createOnDeviceSpeechRecognizer` 使用；是否可用取决于设备和已安装语言模型。
- “Android 系统服务”可能本地也可能云端，OpenTypeless 会单独标识，不宣称它一定离线。
- 可选 OpenTypeless 离线路径的识别音频不会离开设备；只有下载固定模型时联网，并且下载请求不携带服务商凭证。
- BYOK 音频只直连用户配置的 `/audio/transcriptions`；开启 Smart、Translate 或选区编辑时，所需文本才直连 `/chat/completions`。
- 禁止 HTTP 重定向；不向界面回显服务端错误正文；响应大小有上限；凭证和 header 控制字符会在联网前校验。
- 公网地址必须 HTTPS。只有用户明确填写 localhost、链路本地或私有 LAN 自建服务时才允许 HTTP；除本机回环地址外，Bearer/API 密钥必须使用 HTTPS，明文 LAN 服务需将密钥留空。
- 密码字段不能开始录音；Android 备份与设备迁移已禁用；设置、历史和管理页使用 `FLAG_SECURE`。

## 安装与使用

1. 安装 `android/app/build/outputs/apk/debug/app-debug.apk`，或安装经过正确签名的 release APK。
2. 打开 **OpenTypeless Voice Studio**，授予麦克风权限，并确认识别路径。只有平台报告可用时，才会默认选择设备端。
3. 可选下载质量优先离线模型，或配置 BYOK STT 和 LLM。AI、历史、光标前上下文默认全部关闭。
4. 启用 OpenTypeless IME。若要使用任一 Android 标准语音入口，先配置可用的 BYOK STT，显式开启“Android 标准语音入口”，并把调用方的准确包名加入白名单；之后再把 OpenTypeless 选为语音识别服务，或由该白名单 App 启动其 `RecognizerIntent` Activity。部分闭源键盘会硬编码自家语音服务，此时使用独立 IME 或系统键盘切换。
5. 选择 Auto、Exact、Smart 或 Translate 后，可点“开始说话”，也可按住空格说话、松手结束。本地实时文字是可修订的临时 composing text，最终识别会原位替换它；编辑选中文字时，结果返回前必须仍是同一个选区。

两个导出的标准语音入口都只使用 BYOK STT，并且默认关闭；包名白名单与请求限流可以阻止任意拥有麦克风权限的 App 消耗用户的 provider 配额。注册为系统语音服务后若再次调用系统 recognizer，可能解析回自己。独立 IME 支持 OpenTypeless 离线、Android 设备端、系统服务和 BYOK 四种后端。

## 构建与验收

需要 JDK 17、Android SDK Platform 35、Build Tools 35.x。

```bash
cd android
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
python3 scripts/build_sherpa_asr_runtime.py --verify-aar app/libs/sherpa-onnx-asr-1.13.4.aar
./gradlew clean testDebugUnitTest lintRelease assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest  # 连接 API 35+ 模拟器或设备
```

仓库内的原生运行时支持 64 位 ARM 真机与 x86_64 模拟器。若要从固定源码重新构建该 AAR，
还需要 Android NDK r27d；审计构建命令和输入来源见
`scripts/build_sherpa_asr_runtime.py --help`。

自动化测试覆盖：确定性个性化、NFKC span 映射、prompt 信任边界、事实完整性、VAD、取消状态、编辑目标身份、HTTP 重定向/错误/header、RecognitionService 契约、真实 SQLite 导入事务、Android Keystore 历史加密与旧明文迁移。显式大模型门槛还覆盖固定版本真实下载、精确哈希、arm64 原生加载/识别和内存测量。常规 CI 不使用真实 API Key，也不会重复下载 229 MiB 模型，会执行 JVM、Lint、APK 构建和 API 35 模拟器测试。

精确的验收矩阵、产物和已知边界见
[2026-08-09 验收报告](docs/2026-08-09-byok-android-acceptance.md)。

本地生成的 release APK 默认未签名；没有完成签名和校验和发布前，不应把它当成可信正式包分发。

## 桌面端

桌面端保留全局听写、选区操作、翻译、Ask、词典、历史、场景和 App 感知工作流，全部走本地或用户配置的 provider；本 fork 不包含商业账户运行链路。

```bash
npm ci
npm run build
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri build
```

## 诚实边界

Android 0.2 是语音输入层，不是重新发明的一套完整 QWERTY/滑行键盘；标准 Android 三入口就是与成熟键盘协作的方案。设备端识别并非每台设备、每种语言都有。仓库现在已经发布了一个可复现的桌面端候选筛选基准，并据此否决了首个离线模型，但尚未完成跨设备延迟、电量、未见过的手机盲测集或与 Typeless 的正面对比。因此这里主张的是已经能从代码和测试验证的优势：可离线路由、后端自由、显式专名学习、输入目标绑定、事实保护、AI 可撤回，而不是宣称所有语言的识别准确率都必然更高。

## 许可证

MIT，详见 [LICENSE](LICENSE)。本 fork 保留上游版权和归属，不使用 Typeless 的品牌资产或代码，也不是上游托管服务。

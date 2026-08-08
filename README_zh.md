# OpenTypeless BYOK + Android

[English](README.md)

这个 fork 将 OpenTypeless 改造成纯 BYOK（自备密钥/自建服务）的语音输入项目，并新增原生 Android 系统输入法。代码中不再提供 OpenTypeless 账户、订阅、结账、额度或托管云运行链路。

- Fork：[dengxuezhao/opentypeless](https://github.com/dengxuezhao/opentypeless)
- 上游：[tover0314-w/opentypeless](https://github.com/tover0314-w/opentypeless)

## 已实现

| 平台 | 输入方式 | 语音识别 | 可选润色 |
| --- | --- | --- | --- |
| macOS / Windows / Linux | 全局快捷键 + 悬浮胶囊 | 内置 BYOK 提供商或自定义 Whisper 兼容端点 | OpenAI 兼容 LLM，也支持本地 Ollama |
| Android 8.0+ | 可在任意输入框使用的系统输入法（IME） | OpenAI 兼容 `/audio/transcriptions` | OpenAI 兼容 `/chat/completions` |

桌面版保留听写、选中文本编辑、翻译、Ask Anything、本地词典、历史记录、场景和应用感知写作。旧配置如果选择过 `cloud`，启动后会自动迁移到可编辑的 BYOK 默认项。

## 隐私与安全

- 请求从设备直接发送到你配置的 STT/LLM 地址，不经过 OpenTypeless 托管服务。
- 桌面端密钥在系统支持时写入操作系统凭据保险库。
- Android 密钥使用 Android Keystore 中不可导出的 AES-GCM 密钥加密，并关闭系统备份。
- Android 润色提示词将转写文本视为不可信数据，只把模型返回文本写入当前输入框。
- 建议只用 HTTPS。为了兼容 localhost/LAN 自建服务，Android 允许用户主动填写 HTTP 地址；在不可信网络中使用 HTTP 会暴露音频和文本。
- 桌面自动更新已关闭，避免上游更新重新带回已移除的商业代码；等本 fork 拥有自己的签名发布链路后再启用。

项目不附带托管服务。费用、数据保存和隐私规则由你选择的服务端决定。

## Android 使用

1. 构建或安装 `android/app/build/outputs/apk/debug/app-debug.apk`。
2. 打开 OpenTypeless，填写 STT 基础地址、所需 API Key 和模型。
3. 如需润色，再填写 LLM 兼容端点并打开润色。
4. 授予麦克风权限，启用“OpenTypeless Voice Keyboard”，再从系统输入法选择器切换过去。
5. 在任意输入框点击 **Speak**，说完点击 **Stop**；输入法会转写、按需润色并插入文本。

Android 端以 16 kHz 单声道 PCM16 录音，单次上限 60 秒，支持取消；只有光标前文本仍与最近一次插入完全一致时，“Undo voice”才会删除它。

### Android 构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。

```bash
cd android
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest lintRelease assembleDebug assembleRelease
```

## 桌面构建

```bash
npm ci
npm run build
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri build
```

完整检查：

```bash
npm run lint
npm run format:check
npm test
cargo fmt --check --manifest-path src-tauri/Cargo.toml
cargo clippy --all-targets --manifest-path src-tauri/Cargo.toml -- -D warnings
```

## 当前边界

- Android v0.1 是可用的系统级语音输入伴侣，不是桌面端每个设置页的一比一移植。
- Android 当前支持 OpenAI 兼容的转写和聊天完成接口；各厂商专有实时 WebSocket 协议仍只在桌面端提供。
- CI 不使用真实密钥做外部网络调用。单元测试覆盖地址校验、WAV 文件结构和提示词安全，另外执行 Android Lint 与 APK 构建。
- 正式发布 APK 时必须使用私有 release keystore 签名，仓库不保存签名密钥。

## 许可证与归属

采用 MIT 许可证，见 [LICENSE](LICENSE)。本 fork 基于 OpenTypeless，保留上游版权与许可证，由 fork 维护者独立维护，不代表上游托管服务。

<p align="center">
  <a href="README.md">English</a> | <a href="README_zh.md">中文</a> | <a href="README_ja.md">日本語</a> | <a href="README_ko.md">한국어</a> | <a href="README_es.md">Español</a> | <a href="README_fr.md">Français</a> | <a href="README_de.md">Deutsch</a> | <a href="README_pt.md">Português</a> | <a href="README_ru.md">Русский</a> | <a href="README_ar.md">العربية</a> | <strong>हिन्दी</strong> | <a href="README_it.md">Italiano</a> | <a href="README_tr.md">Türkçe</a> | <a href="README_vi.md">Tiếng Việt</a> | <a href="README_th.md">ภาษาไทย</a> | <a href="README_id.md">Bahasa Indonesia</a> | <a href="README_pl.md">Polski</a> | <a href="README_nl.md">Nederlands</a>
</p>

<p align="center">
  <img src="src-tauri/icons/128x128@2x.png" width="128" height="128" alt="OpenTypeless लोगो" />
</p>

<h1 align="center">OpenTypeless</h1>

<p align="center">
  डेस्कटॉप के लिए ओपन-सोर्स AI वॉइस इनपुट। स्वाभाविक रूप से बोलें, किसी भी ऐप में परिष्कृत टेक्स्ट प्राप्त करें।
</p>

<p align="center">
  चाहे आप ईमेल लिख रहे हों, कोडिंग कर रहे हों, चैट कर रहे हों, या नोट्स ले रहे हों — बस एक हॉटकी दबाएं,<br/>
  अपनी बात कहें, और OpenTypeless AI की मदद से आपके शब्दों को ट्रांसक्राइब और पॉलिश करेगा,<br/>
  फिर उन्हें सीधे आपके द्वारा उपयोग किए जा रहे ऐप में टाइप करेगा।
</p>

<p align="center">
  <a href="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml"><img src="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/releases"><img src="https://img.shields.io/github/v/release/tover0314-w/opentypeless?color=2ABBA7" alt="रिलीज़" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tover0314-w/opentypeless" alt="लाइसेंस" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/stargazers"><img src="https://img.shields.io/github/stars/tover0314-w/opentypeless?style=social" alt="Stars" /></a>
  <a href="https://discord.gg/V6rRpJ4RGD"><img src="https://img.shields.io/badge/Discord-Join%20us-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

<p align="center">
  <img src="docs/images/v1.1.49-app-context-showcase.jpg" width="820" alt="Gmail, Slack, Google Docs, Cursor, Zendesk और LinkedIn के अनुरूप OpenTypeless ऐप-जागरूक वॉइस टाइपिंग" />
</p>

<p align="center">
  <img src="docs/images/voice-flow-demo.gif" width="720" alt="OpenTypeless डेमो" />
</p>

## मुख्य विशेषताएँ

- **ऐप-जागरूक लेखन** सक्रिय ऐप को स्थानीय रूप से पहचानता है और ईमेल, चैट, दस्तावेज़, इश्यू ट्रैकर, डेवलपमेंट टूल तथा अन्य कार्यों के लिए संरचना और शैली बदलता है।
- **वॉइस इंटेंट रूटिंग** अंग्रेज़ी, सरलीकृत चीनी और पारंपरिक चीनी में डिक्टेशन, चुने हुए टेक्स्ट का संपादन, अनुवाद, Ask Anything और समर्थित वॉइस कार्रवाइयों को अलग पहचानती है।
- **हर वर्कफ़्लो के लिए कई शॉर्टकट** डिक्टेशन, Ask Anything और अनुवाद में एक से अधिक कुंजी संयोजन जोड़ने और उनका क्रम बदलने देते हैं।
- **बदलने योग्य अनुवाद लक्ष्य** एक ही आउटपुट भाषा तय रखने के बजाय आपकी उपयोगी भाषाओं के बीच तेज़ी से स्विच करने देते हैं।
- **बेहतर स्थानीय डिक्शनरी** सुधार नियमों के साथ डिक्शनरी इम्पोर्ट और एक्सपोर्ट जोड़ती है।
- **हर ऐप के लिए शैली मैपिंग** किसी ऐप को अलग लेखन शैली चाहिए तो उसकी अंतर्निहित श्रेणी को बदलने देती है।

ऐप पहचान, मैपिंग, डिक्शनरी प्रविष्टियाँ और सुधार नियम स्थानीय रूप से संग्रहीत होते हैं। ऐप-जागरूक पॉलिश केवल आंतरिक ऐप श्रेणी और स्वीकृत शैली मेटाडेटा को कॉन्फ़िगर किए गए LLM पथ पर भेजता है; कच्चे विंडो शीर्षक और दस्तावेज़ सामग्री ऐप संदर्भ के रूप में नहीं भेजे जाते और इतिहास में संग्रहीत नहीं होते।

| ऐप-जागरूक AI पॉलिश | स्थानीय डिक्शनरी और सुधार |
| --- | --- |
| <img src="docs/images/v1.1.49-app-aware-polish.jpg" width="420" alt="OpenTypeless v1.1.49 ऐप-जागरूक AI पॉलिश" /> | <img src="docs/images/v1.1.49-dictionary.jpg" width="420" alt="OpenTypeless v1.1.49 स्थानीय डिक्शनरी और सुधार" /> |

<details>
<summary>और स्क्रीनशॉट</summary>

<p align="center">
  <img src="docs/images/app-main-light.png" width="720" alt="OpenTypeless मुख्य विंडो" />
</p>

| सेटिंग्स | इतिहास |
|---|---|
| <img src="docs/images/app-settings.png" width="360" /> | <img src="docs/images/app-history.png" width="360" /> |

</details>

---

## OpenTypeless क्यों?

| | OpenTypeless | macOS डिक्टेशन | Windows वॉइस टाइपिंग | Whisper Desktop |
|---|---|---|---|---|
| AI टेक्स्ट पॉलिशिंग | ✅ अनेक LLM | ❌ | ❌ | ❌ |
| STT प्रोवाइडर चॉइस | ✅ 6+ प्रोवाइडर | ❌ केवल Apple | ❌ केवल Microsoft | ❌ केवल Whisper |
| किसी भी ऐप में काम करता है | ✅ | ✅ | ✅ | ❌ कॉपी-पेस्ट |
| अनुवाद मोड | ✅ | ❌ | ❌ | ❌ |
| ओपन सोर्स | ✅ MIT | ❌ | ❌ | ✅ |
| क्रॉस-प्लेटफ़ॉर्म | ✅ Win/Mac/Linux | ❌ केवल Mac | ❌ केवल Windows | ✅ |
| कस्टम डिक्शनरी | ✅ | ❌ | ❌ | ❌ |
| सेल्फ-होस्टेबल | ✅ BYOK | ❌ | ❌ | ✅ |

## विशेषताएं

- 🎙️ ग्लोबल हॉटकी रिकॉर्डिंग — होल्ड-टू-रिकॉर्ड या टॉगल मोड
- 💊 फ्लोटिंग कैप्सूल विजेट, हमेशा सबसे ऊपर
- 🗣️ 6+ STT प्रोवाइडर: Deepgram, AssemblyAI, Whisper, Groq, GLM-ASR, SiliconFlow
- 🤖 अनेक LLM से टेक्स्ट पॉलिशिंग: OpenAI, DeepSeek, Claude, Gemini, Ollama और अन्य
- ⚡ स्ट्रीमिंग आउटपुट — टेक्स्ट LLM जनरेट करते ही दिखाई देता है
- ⌨️ कीबोर्ड सिमुलेशन या क्लिपबोर्ड आउटपुट
- 📝 रिकॉर्डिंग से पहले टेक्स्ट हाइलाइट करें ताकि LLM को संदर्भ मिले
- 🌐 अनुवाद मोड: एक भाषा में बोलें, दूसरी में आउटपुट पाएं (20+ भाषाएं)
- 📖 विशेष शब्दों के लिए कस्टम डिक्शनरी
- 🔍 ऐप डिटेक्शन से फॉर्मेटिंग अनुकूलन
- 📜 फुल-टेक्स्ट सर्च के साथ लोकल हिस्ट्री
- 🌗 डार्क / लाइट / सिस्टम थीम
- 🚀 लॉगिन पर ऑटो-स्टार्ट

> [!TIP]
> **सर्वश्रेष्ठ अनुभव के लिए अनुशंसित कॉन्फ़िगरेशन**
>
> | | प्रोवाइडर | मॉडल |
> |---|---|---|
> | 🗣️ STT | Groq | `whisper-large-v3-turbo` |
> | 🤖 AI पॉलिश | Google | `gemini-2.5-flash` |
>
> यह कॉम्बो तेज, सटीक ट्रांसक्रिप्शन और उच्च-गुणवत्ता टेक्स्ट पॉलिशिंग प्रदान करता है — और दोनों उदार मुफ्त टियर प्रदान करते हैं।

## डाउनलोड

अपने प्लेटफ़ॉर्म के लिए नवीनतम संस्करण डाउनलोड करें:

**[Releases से डाउनलोड करें](https://github.com/tover0314-w/opentypeless/releases)**

| प्लेटफ़ॉर्म | फ़ाइल |
|------------|-------|
| Windows | `.msi` इंस्टॉलर |
| macOS (Apple Silicon) | `.dmg` |
| macOS (Intel) | `.dmg` |
| Linux | `.AppImage` / `.deb` |

## पूर्वापेक्षाएं

- [Node.js](https://nodejs.org/) 20+
- [Rust](https://rustup.rs/) (stable toolchain)
- Tauri के लिए प्लेटफ़ॉर्म-विशिष्ट निर्भरताएं: देखें [Tauri पूर्वापेक्षाएं](https://v2.tauri.app/start/prerequisites/)

## शुरुआत करना

```bash
# निर्भरताएं इंस्टॉल करें
npm install

# डेवलपमेंट मोड में चलाएं
npm run tauri dev

# प्रोडक्शन के लिए बिल्ड करें
npm run tauri build
```

बिल्ड किया गया ऐप्लिकेशन `src-tauri/target/release/bundle/` में होगा।

## कॉन्फ़िगरेशन

सभी सेटिंग्स ऐप के सेटिंग्स पैनल से उपलब्ध हैं:

- **स्पीच रिकग्निशन** — STT प्रोवाइडर चुनें और API कुंजी दर्ज करें
- **AI पॉलिश** — LLM प्रोवाइडर, मॉडल और API कुंजी चुनें
- **सामान्य** — हॉटकी, आउटपुट मोड, थीम, ऑटो-स्टार्ट
- **डिक्शनरी** — बेहतर ट्रांसक्रिप्शन सटीकता के लिए कस्टम शब्द जोड़ें
- **सीन** — विभिन्न उपयोग मामलों के लिए प्रॉम्प्ट टेम्पलेट

API कुंजियां `tauri-plugin-store` के माध्यम से स्थानीय रूप से संग्रहीत की जाती हैं। कोई भी कुंजी OpenTypeless सर्वर को नहीं भेजी जाती — सभी STT/LLM अनुरोध सीधे आपके द्वारा कॉन्फ़िगर किए गए प्रोवाइडर को जाते हैं।

### Cloud (Pro) विकल्प

OpenTypeless एक वैकल्पिक Pro सब्सक्रिप्शन भी प्रदान करता है जो प्रबंधित STT और LLM कोटा प्रदान करता है ताकि आपको अपनी API कुंजियों की आवश्यकता न हो। यह पूरी तरह से वैकल्पिक है — ऐप आपकी अपनी कुंजियों के साथ पूरी तरह कार्यात्मक है।

[Pro के बारे में अधिक जानें](https://www.opentypeless.com)

### BYOK (Bring Your Own Key) बनाम Cloud

| | BYOK मोड | Cloud (Pro) मोड |
|---|---|---|
| STT | आपकी अपनी API कुंजी (Deepgram, AssemblyAI, आदि) | प्रबंधित कोटा (10 घंटे/माह) |
| LLM | आपकी अपनी API कुंजी (OpenAI, DeepSeek, आदि) | प्रबंधित कोटा (~50 लाख टोकन/माह) |
| क्लाउड निर्भरता | कोई नहीं — सभी अनुरोध सीधे आपके प्रोवाइडर को जाते हैं | www.opentypeless.com से कनेक्शन आवश्यक |
| लागत | सीधे अपने प्रोवाइडर को भुगतान करें | $4.99/माह सब्सक्रिप्शन |

सभी कोर विशेषताएं — रिकॉर्डिंग, ट्रांसक्रिप्शन, AI पॉलिश, कीबोर्ड/क्लिपबोर्ड आउटपुट, डिक्शनरी, हिस्ट्री — BYOK मोड में OpenTypeless सर्वर से पूरी तरह स्वतंत्र रूप से काम करती हैं।

### सेल्फ-होस्टिंग / बिना क्लाउड

बिना किसी क्लाउड निर्भरता के OpenTypeless चलाने के लिए:

1. सेटिंग्स में कोई भी गैर-Cloud STT और LLM प्रोवाइडर चुनें
2. अपनी API कुंजियां दर्ज करें
3. बस — www.opentypeless.com पर कोई अकाउंट या इंटरनेट कनेक्शन की आवश्यकता नहीं

यदि आप वैकल्पिक क्लाउड सुविधाओं को अपने बैकएंड पर इंगित करना चाहते हैं, तो बिल्ड से पहले ये पर्यावरण चर सेट करें:

| चर | डिफ़ॉल्ट | विवरण |
|---|---|---|
| `VITE_API_BASE_URL` | `https://www.opentypeless.com` | फ्रंटएंड क्लाउड API बेस URL |
| `API_BASE_URL` | `https://www.opentypeless.com` | Rust बैकएंड क्लाउड API बेस URL |

```bash
# उदाहरण: कस्टम बैकएंड के साथ बिल्ड
VITE_API_BASE_URL=https://my-server.example.com API_BASE_URL=https://my-server.example.com npm run tauri build
```

## आर्किटेक्चर

**डेटा फ्लो पाइपलाइन:**

```
माइक्रोफ़ोन → ऑडियो कैप्चर → STT प्रोवाइडर → रॉ ट्रांसक्रिप्ट → LLM पॉलिश → कीबोर्ड/क्लिपबोर्ड आउटपुट
```

```
src/                  # React फ्रंटएंड (TypeScript)
├── components/       # UI कंपोनेंट्स (सेटिंग्स, हिस्ट्री, कैप्सूल, आदि)
├── hooks/            # React hooks (रिकॉर्डिंग, थीम, Tauri इवेंट्स)
├── lib/              # यूटिलिटीज (API क्लाइंट, राउटर, कॉन्स्टेंट्स)
└── stores/           # Zustand स्टेट मैनेजमेंट

src-tauri/src/        # Rust बैकएंड
├── audio/            # cpal से ऑडियो कैप्चर
├── stt/              # STT प्रोवाइडर (Deepgram, AssemblyAI, Whisper-संगत, Cloud)
├── llm/              # LLM प्रोवाइडर (OpenAI-संगत, Cloud)
├── output/           # टेक्स्ट आउटपुट (कीबोर्ड सिमुलेशन, क्लिपबोर्ड पेस्ट)
├── storage/          # कॉन्फ़िग (tauri-plugin-store) + हिस्ट्री/डिक्शनरी (SQLite)
├── app_detector/     # सक्रिय ऐप्लिकेशन डिटेक्शन
├── pipeline.rs       # रिकॉर्डिंग → STT → LLM → आउटपुट ऑर्केस्ट्रेशन
└── lib.rs            # Tauri ऐप सेटअप, कमांड्स, हॉटकी हैंडलिंग
```

## रोडमैप

- [ ] कस्टम STT/LLM इंटीग्रेशन के लिए प्लगइन सिस्टम
- [ ] बहुभाषी STT सटीकता और बोली समर्थन में सुधार
- [ ] वॉइस कमांड्स
- [ ] कस्टमाइज़ेबल हॉटकी कॉम्बिनेशन
- [ ] बेहतर ऑनबोर्डिंग अनुभव
- [ ] मोबाइल कंपेनियन ऐप

## FAQ

**क्या मेरा ऑडियो क्लाउड पर भेजा जाता है?**
BYOK मोड में, ऑडियो सीधे आपके चुने हुए STT प्रोवाइडर (जैसे Groq, Deepgram) को जाता है। OpenTypeless सर्वर से कुछ नहीं गुजरता। Cloud (Pro) मोड में, ऑडियो हमारे प्रबंधित प्रॉक्सी को ट्रांसक्रिप्शन के लिए भेजा जाता है।

**क्या मैं इसे ऑफ़लाइन उपयोग कर सकता हूं?**
लोकल STT प्रोवाइडर (Ollama के माध्यम से Whisper) और लोकल LLM (Ollama) के साथ, ऐप पूरी तरह ऑफ़लाइन काम करता है। इंटरनेट कनेक्शन की आवश्यकता नहीं।

**कौन सी भाषाएं समर्थित हैं?**
STT प्रोवाइडर के आधार पर 99+ भाषाओं का समर्थन करता है। AI पॉलिश और अनुवाद 20+ लक्ष्य भाषाओं का समर्थन करते हैं।

**क्या ऐप मुफ्त है?**
हां। ऐप आपकी अपनी API कुंजियों (BYOK) के साथ पूरी तरह कार्यात्मक है। Cloud Pro सब्सक्रिप्शन ($4.99/माह) वैकल्पिक है।

## समुदाय

- 💬 [Discord](https://discord.gg/V6rRpJ4RGD) — चैट, मदद, फ़ीडबैक
- 🗣️ [GitHub Discussions](https://github.com/tover0314-w/opentypeless/discussions) — फ़ीचर प्रस्ताव, प्रश्न और उत्तर
- 🐛 [Issue Tracker](https://github.com/tover0314-w/opentypeless/issues) — बग रिपोर्ट और फ़ीचर अनुरोध
- 📖 [योगदान गाइड](CONTRIBUTING.md) — डेवलपमेंट सेटअप और दिशानिर्देश
- 🔒 [सुरक्षा नीति](SECURITY.md) — जिम्मेदारी से कमज़ोरियों की रिपोर्ट करें
- 🧭 [विज़न](VISION.md) — प्रोजेक्ट सिद्धांत और रोडमैप दिशा

## योगदान

योगदान का स्वागत है! डेवलपमेंट सेटअप और दिशानिर्देशों के लिए [CONTRIBUTING.md](CONTRIBUTING.md) देखें।

शुरू करने की जगह खोज रहे हैं? [`good first issue`](https://github.com/tover0314-w/opentypeless/labels/good%20first%20issue) लेबल वाले issues देखें।

## Star History

<a href="https://star-history.com/#tover0314-w/opentypeless&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date" />
    <img alt="Star History चार्ट" src="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date" />
  </picture>
</a>

## Claude Code से एक दिन में बनाया गया

यह पूरा प्रोजेक्ट [Claude Code](https://claude.com/claude-code) का उपयोग करके एक दिन में बनाया गया — आर्किटेक्चर डिज़ाइन से लेकर पूर्ण कार्यान्वयन तक, जिसमें Tauri बैकएंड, React फ्रंटएंड, CI/CD पाइपलाइन और यह README शामिल है।

## लाइसेंस

[MIT](LICENSE)

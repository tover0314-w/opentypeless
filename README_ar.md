<p align="center">
  <a href="README.md">English</a> | <a href="README_zh.md">中文</a> | <a href="README_ja.md">日本語</a> | <a href="README_ko.md">한국어</a> | <a href="README_es.md">Español</a> | <a href="README_fr.md">Français</a> | <a href="README_de.md">Deutsch</a> | <a href="README_pt.md">Português</a> | <a href="README_ru.md">Русский</a> | <strong>العربية</strong> | <a href="README_hi.md">हिन्दी</a> | <a href="README_it.md">Italiano</a> | <a href="README_tr.md">Türkçe</a> | <a href="README_vi.md">Tiếng Việt</a> | <a href="README_th.md">ภาษาไทย</a> | <a href="README_id.md">Bahasa Indonesia</a> | <a href="README_pl.md">Polski</a> | <a href="README_nl.md">Nederlands</a>
</p>

<p align="center">
  <img src="src-tauri/icons/128x128@2x.png" width="128" height="128" alt="شعار OpenTypeless" />
</p>

<h1 align="center">OpenTypeless</h1>

<p align="center">
  إدخال صوتي بالذكاء الاصطناعي مفتوح المصدر لسطح المكتب. تحدث بطبيعية، واحصل على نص منقح في أي تطبيق.
</p>

<p align="center">
  سواء كنت تكتب رسائل بريد إلكتروني، أو تبرمج، أو تتحدث، أو تدوّن ملاحظات — فقط اضغط على مفتاح الاختصار،<br/>
  قل ما تفكر فيه، وسيقوم OpenTypeless بتحويل كلامك إلى نص وتنقيحه بالذكاء الاصطناعي،<br/>
  ثم يكتبه مباشرة في أي تطبيق تستخدمه.
</p>

<p align="center">
  <a href="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml"><img src="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/releases"><img src="https://img.shields.io/github/v/release/tover0314-w/opentypeless?color=2ABBA7" alt="الإصدار" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tover0314-w/opentypeless" alt="الترخيص" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/stargazers"><img src="https://img.shields.io/github/stars/tover0314-w/opentypeless?style=social" alt="النجوم" /></a>
  <a href="https://discord.gg/V6rRpJ4RGD"><img src="https://img.shields.io/badge/Discord-Join%20us-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

<p align="center">
  <img src="docs/images/v1.1.49-app-context-showcase.jpg" width="820" alt="إدخال OpenTypeless الصوتي المتكيف مع Gmail وSlack وGoogle Docs وCursor وZendesk وLinkedIn" />
</p>

<p align="center">
  <img src="docs/images/voice-flow-demo.gif" width="720" alt="عرض OpenTypeless" />
</p>

## الجديد في v1.1.49

- **الكتابة الواعية بالتطبيق** تكتشف التطبيق النشط محلياً وتكيّف البنية والأسلوب للبريد والدردشة والمستندات وأدوات تتبع المشكلات وأدوات التطوير وغيرها.
- **توجيه النية الصوتية** يميّز بين الإملاء وتحرير النص المحدد والترجمة وAsk Anything والإجراءات الصوتية المدعومة بالإنجليزية والصينية المبسطة والتقليدية.
- **اختصارات متعددة لكل سير عمل** تتيح إضافة أكثر من تعيين وترتيبه للإملاء وAsk Anything والترجمة.
- **أهداف ترجمة قابلة للتبديل** تسهّل التنقل بين اللغات المستخدمة بدلاً من تثبيت لغة إخراج واحدة.
- **قاموس محلي أقوى** يضيف قواعد التصحيح واستيراد القاموس وتصديره.
- **تعيينات نمط لكل تطبيق** تتيح تجاوز الفئة المدمجة عندما يحتاج التطبيق إلى أسلوب كتابة مختلف.

يتم تخزين اكتشاف التطبيقات والتعيينات وإدخالات القاموس وقواعد التصحيح محلياً. لا ترسل المعالجة الواعية بالتطبيق إلى مسار LLM المحدد سوى فئة التطبيق الداخلية وبيانات النمط المعتمدة؛ ولا يتم إرسال عناوين النوافذ الخام أو محتوى المستندات كسياق للتطبيق أو حفظها في السجل.

| تحسين بالذكاء الاصطناعي حسب التطبيق | القاموس المحلي والتصحيحات |
| --- | --- |
| <img src="docs/images/v1.1.49-app-aware-polish.jpg" width="420" alt="تحسين OpenTypeless v1.1.49 حسب التطبيق" /> | <img src="docs/images/v1.1.49-dictionary.jpg" width="420" alt="قاموس OpenTypeless v1.1.49 المحلي وتصحيحاته" /> |

<details>
<summary>المزيد من لقطات الشاشة</summary>

<p align="center">
  <img src="docs/images/app-main-light.png" width="720" alt="النافذة الرئيسية لـ OpenTypeless" />
</p>

| الإعدادات | السجل |
|---|---|
| <img src="docs/images/app-settings.png" width="360" /> | <img src="docs/images/app-history.png" width="360" /> |

</details>

---

## لماذا OpenTypeless؟

| | OpenTypeless | إملاء macOS | الكتابة الصوتية في Windows | Whisper Desktop |
|---|---|---|---|---|
| تنقيح النص بالذكاء الاصطناعي | ✅ عدة نماذج LLM | ❌ | ❌ | ❌ |
| اختيار مزود STT | ✅ أكثر من 6 مزودين | ❌ Apple فقط | ❌ Microsoft فقط | ❌ Whisper فقط |
| يعمل في أي تطبيق | ✅ | ✅ | ✅ | ❌ نسخ ولصق |
| وضع الترجمة | ✅ | ❌ | ❌ | ❌ |
| مفتوح المصدر | ✅ MIT | ❌ | ❌ | ✅ |
| متعدد المنصات | ✅ Win/Mac/Linux | ❌ Mac فقط | ❌ Windows فقط | ✅ |
| قاموس مخصص | ✅ | ❌ | ❌ | ❌ |
| استضافة ذاتية | ✅ BYOK | ❌ | ❌ | ✅ |

## الميزات

- 🎙️ مفتاح اختصار عالمي — اضغط باستمرار أو تبديل
- 💊 عنصر كبسولة عائم، دائمًا في المقدمة
- 🗣️ أكثر من 6 مزودي STT: Deepgram، AssemblyAI، Whisper، Groq، GLM-ASR، SiliconFlow
- 🤖 تنقيح النص عبر عدة نماذج LLM: OpenAI، DeepSeek، Claude، Gemini، Ollama والمزيد
- ⚡ إخراج متدفق — يظهر النص أثناء توليده
- ⌨️ محاكاة لوحة المفاتيح أو إخراج الحافظة
- 📝 حدد النص قبل التسجيل لإعطاء سياق للنموذج
- 🌐 وضع الترجمة: تحدث بلغة واحصل على الإخراج بلغة أخرى (أكثر من 20 لغة)
- 📖 قاموس مخصص للمصطلحات المتخصصة
- 🔍 كشف التطبيق لتكييف التنسيق
- 📜 سجل محلي مع بحث نصي كامل
- 🌗 سمة داكنة / فاتحة / النظام
- 🚀 بدء تلقائي عند تسجيل الدخول

> [!TIP]
> **التكوين الموصى به لأفضل تجربة**
>
> | | المزود | النموذج |
> |---|---|---|
> | 🗣️ STT | Groq | `whisper-large-v3-turbo` |
> | 🤖 تنقيح AI | Google | `gemini-2.5-flash` |
>
> توفر هذه المجموعة نسخًا سريعًا ودقيقًا مع تنقيح نصي عالي الجودة — وكلاهما يقدم مستويات مجانية سخية.

## التحميل

حمّل أحدث إصدار لمنصتك:

**[التحميل من Releases](https://github.com/tover0314-w/opentypeless/releases)**

| المنصة | الملف |
|--------|-------|
| Windows | مثبت `.msi` |
| macOS (Apple Silicon) | `.dmg` |
| macOS (Intel) | `.dmg` |
| Linux | `.AppImage` / `.deb` |

## المتطلبات المسبقة

- [Node.js](https://nodejs.org/) 20+
- [Rust](https://rustup.rs/) (سلسلة أدوات مستقرة)
- تبعيات خاصة بالمنصة لـ Tauri: انظر [متطلبات Tauri المسبقة](https://v2.tauri.app/start/prerequisites/)

## البدء

```bash
# تثبيت التبعيات
npm install

# التشغيل في وضع التطوير
npm run tauri dev

# البناء للإنتاج
npm run tauri build
```

سيكون التطبيق المبني في `src-tauri/target/release/bundle/`.

## التكوين

جميع الإعدادات متاحة من لوحة الإعدادات داخل التطبيق:

- **التعرف على الكلام** — اختر مزود STT وأدخل مفتاح API
- **تنقيح AI** — اختر مزود LLM والنموذج ومفتاح API
- **عام** — مفتاح الاختصار، وضع الإخراج، السمة، البدء التلقائي
- **القاموس** — أضف مصطلحات مخصصة لتحسين دقة النسخ
- **المشاهد** — قوالب أوامر لحالات استخدام مختلفة

يتم تخزين مفاتيح API محليًا عبر `tauri-plugin-store`. لا يتم إرسال أي مفاتيح إلى خوادم OpenTypeless — جميع طلبات STT/LLM تذهب مباشرة إلى المزود الذي تختاره.

### خيار Cloud (Pro)

يقدم OpenTypeless أيضًا اشتراك Pro اختياري يوفر حصة مُدارة من STT و LLM حتى لا تحتاج إلى مفاتيح API خاصة بك. هذا اختياري تمامًا — التطبيق يعمل بالكامل بمفاتيحك الخاصة.

[اعرف المزيد عن Pro](https://www.opentypeless.com)

### BYOK (أحضر مفتاحك) مقابل Cloud

| | وضع BYOK | وضع Cloud (Pro) |
|---|---|---|
| STT | مفتاح API الخاص بك (Deepgram، AssemblyAI، إلخ) | حصة مُدارة (10 ساعات/شهر) |
| LLM | مفتاح API الخاص بك (OpenAI، DeepSeek، إلخ) | حصة مُدارة (~5 مليون رمز/شهر) |
| تبعية السحابة | لا شيء — جميع الطلبات تذهب مباشرة إلى مزودك | يتطلب اتصالاً بـ www.opentypeless.com |
| التكلفة | ادفع لمزودك مباشرة | اشتراك 4.99 دولار/شهر |

جميع الميزات الأساسية — التسجيل، النسخ، تنقيح AI، إخراج لوحة المفاتيح/الحافظة، القاموس، السجل — تعمل بالكامل بدون خوادم OpenTypeless في وضع BYOK.

### الاستضافة الذاتية / بدون سحابة

لتشغيل OpenTypeless بدون أي تبعية سحابية:

1. اختر أي مزود STT و LLM غير Cloud في الإعدادات
2. أدخل مفاتيح API الخاصة بك
3. هذا كل شيء — لا حاجة لحساب أو اتصال بـ www.opentypeless.com

إذا كنت تريد توجيه ميزات السحابة الاختيارية إلى خادمك الخاص، اضبط متغيرات البيئة هذه قبل البناء:

| المتغير | الافتراضي | الوصف |
|---|---|---|
| `VITE_API_BASE_URL` | `https://www.opentypeless.com` | عنوان URL الأساسي لواجهة API السحابية للواجهة الأمامية |
| `API_BASE_URL` | `https://www.opentypeless.com` | عنوان URL الأساسي لواجهة API السحابية للخلفية Rust |

```bash
# مثال: البناء مع خادم خلفي مخصص
VITE_API_BASE_URL=https://my-server.example.com API_BASE_URL=https://my-server.example.com npm run tauri build
```

## البنية

**خط أنابيب تدفق البيانات:**

```
ميكروفون → التقاط الصوت → مزود STT → نسخ خام → تنقيح LLM → إخراج لوحة المفاتيح/الحافظة
```

```
src/                  # واجهة React الأمامية (TypeScript)
├── components/       # مكونات UI (الإعدادات، السجل، الكبسولة، إلخ)
├── hooks/            # React hooks (التسجيل، السمة، أحداث Tauri)
├── lib/              # أدوات مساعدة (عميل API، الموجه، الثوابت)
└── stores/           # إدارة الحالة Zustand

src-tauri/src/        # خلفية Rust
├── audio/            # التقاط الصوت عبر cpal
├── stt/              # مزودو STT (Deepgram، AssemblyAI، متوافق مع Whisper، Cloud)
├── llm/              # مزودو LLM (متوافق مع OpenAI، Cloud)
├── output/           # إخراج النص (محاكاة لوحة المفاتيح، لصق الحافظة)
├── storage/          # التكوين (tauri-plugin-store) + السجل/القاموس (SQLite)
├── app_detector/     # كشف التطبيق النشط للسياق
├── pipeline.rs       # تنسيق التسجيل → STT → LLM → الإخراج
└── lib.rs            # إعداد تطبيق Tauri، الأوامر، معالجة مفاتيح الاختصار
```

## خارطة الطريق

- [ ] نظام إضافات لتكاملات STT/LLM مخصصة
- [ ] تحسين دقة STT متعدد اللغات ودعم اللهجات
- [ ] أوامر صوتية
- [ ] تركيبات مفاتيح اختصار قابلة للتخصيص
- [ ] تحسين تجربة التعريف بالتطبيق
- [ ] تطبيق مرافق للهاتف المحمول

## الأسئلة الشائعة

**هل يتم إرسال صوتي إلى السحابة؟**
في وضع BYOK، يذهب الصوت مباشرة إلى مزود STT الذي اخترته (مثل Groq، Deepgram). لا شيء يمر عبر خوادم OpenTypeless. في وضع Cloud (Pro)، يُرسل الصوت إلى وكيلنا المُدار للنسخ.

**هل يمكنني استخدامه بدون إنترنت؟**
مع مزود STT محلي (Whisper عبر Ollama) و LLM محلي (Ollama)، يعمل التطبيق بالكامل بدون إنترنت. لا حاجة لاتصال بالإنترنت.

**ما اللغات المدعومة؟**
يدعم STT أكثر من 99 لغة حسب المزود. يدعم تنقيح AI والترجمة أكثر من 20 لغة هدف.

**هل التطبيق مجاني؟**
نعم. التطبيق يعمل بالكامل بمفاتيح API الخاصة بك (BYOK). اشتراك Cloud Pro (4.99 دولار/شهر) اختياري.

## المجتمع

- 💬 [Discord](https://discord.gg/V6rRpJ4RGD) — تحدث، احصل على مساعدة، شارك ملاحظاتك
- 🗣️ [GitHub Discussions](https://github.com/tover0314-w/opentypeless/discussions) — اقتراحات الميزات، أسئلة وأجوبة
- 🐛 [Issue Tracker](https://github.com/tover0314-w/opentypeless/issues) — تقارير الأخطاء وطلبات الميزات
- 📖 [دليل المساهمة](CONTRIBUTING.md) — إعداد التطوير والإرشادات
- 🔒 [سياسة الأمان](SECURITY.md) — الإبلاغ عن الثغرات بمسؤولية
- 🧭 [الرؤية](VISION.md) — مبادئ المشروع واتجاه خارطة الطريق

## المساهمة

المساهمات مرحب بها! انظر [CONTRIBUTING.md](CONTRIBUTING.md) لإعداد التطوير والإرشادات.

تبحث عن نقطة بداية؟ تحقق من المشاكل المُعلَّمة بـ [`good first issue`](https://github.com/tover0314-w/opentypeless/labels/good%20first%20issue).

## Star History

<a href="https://star-history.dera.page/#tover0314-w/opentypeless&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date" />
    <img alt="مخطط Star History" src="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date" />
  </picture>
</a>

## تم بناؤه بواسطة Claude Code في يوم واحد

تم بناء هذا المشروع بالكامل في يوم واحد باستخدام [Claude Code](https://claude.com/claude-code) — من تصميم البنية إلى التنفيذ الكامل، بما في ذلك خلفية Tauri، واجهة React الأمامية، خط أنابيب CI/CD، وهذا الملف التعريفي.

## الترخيص

[MIT](LICENSE)

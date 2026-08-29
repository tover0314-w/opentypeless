<p align="center">
  <a href="README.md">English</a> | <a href="README_zh.md">中文</a> | <a href="README_ja.md">日本語</a> | <a href="README_ko.md">한국어</a> | <a href="README_es.md">Español</a> | <a href="README_fr.md">Français</a> | <a href="README_de.md">Deutsch</a> | <a href="README_pt.md">Português</a> | <a href="README_ru.md">Русский</a> | <a href="README_ar.md">العربية</a> | <a href="README_hi.md">हिन्दी</a> | <a href="README_it.md">Italiano</a> | <a href="README_tr.md">Türkçe</a> | <a href="README_vi.md">Tiếng Việt</a> | <strong>ภาษาไทย</strong> | <a href="README_id.md">Bahasa Indonesia</a> | <a href="README_pl.md">Polski</a> | <a href="README_nl.md">Nederlands</a>
</p>

<p align="center">
  <img src="src-tauri/icons/128x128@2x.png" width="128" height="128" alt="โลโก้ OpenTypeless" />
</p>

<h1 align="center">OpenTypeless</h1>

<p align="center">
  ระบบป้อนข้อมูลด้วยเสียง AI แบบโอเพนซอร์สสำหรับเดสก์ท็อป พูดตามธรรมชาติ ได้ข้อความที่สมบูรณ์ในทุกแอป
</p>

<p align="center">
  ไม่ว่าคุณจะเขียนอีเมล เขียนโค้ด แชท หรือจดบันทึก — แค่กดปุ่มลัด<br/>
  พูดในสิ่งที่คุณคิด แล้ว OpenTypeless จะถอดเสียงและปรับแต่งคำพูดของคุณด้วย AI<br/>
  จากนั้นพิมพ์ลงในแอปที่คุณกำลังใช้งานโดยตรง
</p>

<p align="center">
  <a href="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml"><img src="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/releases"><img src="https://img.shields.io/github/v/release/tover0314-w/opentypeless?color=2ABBA7" alt="เวอร์ชัน" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tover0314-w/opentypeless" alt="สัญญาอนุญาต" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/stargazers"><img src="https://img.shields.io/github/stars/tover0314-w/opentypeless?style=social" alt="ดาว" /></a>
  <a href="https://discord.gg/V6rRpJ4RGD"><img src="https://img.shields.io/badge/Discord-เข้าร่วม-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

<p align="center">
  <img src="docs/images/v1.1.49-app-context-showcase.jpg" width="820" alt="การพิมพ์ด้วยเสียง OpenTypeless ที่ปรับให้เข้ากับ Gmail, Slack, Google Docs, Cursor, Zendesk และ LinkedIn" />
</p>

<p align="center">
  <img src="docs/images/voice-flow-demo.gif" width="720" alt="สาธิต OpenTypeless" />
</p>

## จุดเด่น

- **การเขียนที่รับรู้แอป** ตรวจจับแอปที่กำลังใช้งานภายในเครื่อง แล้วปรับโครงสร้างและน้ำเสียงให้เหมาะกับอีเมล แชต เอกสาร ระบบติดตามปัญหา เครื่องมือพัฒนา และอื่น ๆ
- **การกำหนดเส้นทางเจตนาด้วยเสียง** แยกการป้อนตามคำบอก การแก้ไขข้อความที่เลือก การแปล Ask Anything และคำสั่งเสียงที่รองรับในภาษาอังกฤษ จีนตัวย่อ และจีนตัวเต็ม
- **หลายปุ่มลัดต่อเวิร์กโฟลว์** ช่วยให้เพิ่มและจัดลำดับคีย์ลัดได้มากกว่าหนึ่งชุดสำหรับการป้อนตามคำบอก Ask Anything และการแปล
- **เป้าหมายการแปลที่สลับได้** ทำให้เปลี่ยนระหว่างภาษาที่ใช้เป็นประจำได้รวดเร็ว โดยไม่ต้องกำหนดภาษาเอาต์พุตเพียงภาษาเดียว
- **พจนานุกรมภายในเครื่องที่ดีขึ้น** เพิ่มกฎการแก้ไข รวมถึงการนำเข้าและส่งออกพจนานุกรม
- **การแมปรูปแบบตามแอป** ช่วยให้แทนที่หมวดหมู่ในตัวเมื่อแอปหนึ่งต้องการรูปแบบการเขียนที่ต่างออกไป

การตรวจจับแอป การแมป รายการพจนานุกรม และกฎการแก้ไขจะถูกเก็บไว้ในเครื่อง การปรับข้อความตามแอปจะส่งเฉพาะหมวดหมู่แอปภายในและข้อมูลเมตารูปแบบที่อนุมัติแล้วไปยังเส้นทาง LLM ที่กำหนดค่าไว้ โดยจะไม่ส่งชื่อหน้าต่างแบบดิบหรือเนื้อหาเอกสารเป็นบริบทของแอป และไม่บันทึกไว้ในประวัติ

| การปรับข้อความด้วย AI ตามแอป | พจนานุกรมและการแก้ไขในเครื่อง |
| --- | --- |
| <img src="docs/images/v1.1.49-app-aware-polish.jpg" width="420" alt="การปรับข้อความด้วย AI ตามแอปใน OpenTypeless v1.1.49" /> | <img src="docs/images/v1.1.49-dictionary.jpg" width="420" alt="พจนานุกรมและการแก้ไขในเครื่องของ OpenTypeless v1.1.49" /> |

<details>
<summary>ดูภาพหน้าจอเพิ่มเติม</summary>

<p align="center">
  <img src="docs/images/app-main-light.png" width="720" alt="หน้าต่างหลัก OpenTypeless" />
</p>

| การตั้งค่า | ประวัติ |
|---|---|
| <img src="docs/images/app-settings.png" width="360" /> | <img src="docs/images/app-history.png" width="360" /> |

</details>

---

## ทำไมต้อง OpenTypeless?

| | OpenTypeless | macOS Dictation | Windows Voice Typing | Whisper Desktop |
|---|---|---|---|---|
| ปรับแต่งข้อความด้วย AI | ✅ หลาย LLM | ❌ | ❌ | ❌ |
| เลือกผู้ให้บริการ STT | ✅ 6+ ผู้ให้บริการ | ❌ Apple เท่านั้น | ❌ Microsoft เท่านั้น | ❌ Whisper เท่านั้น |
| ใช้งานได้ในทุกแอป | ✅ | ✅ | ✅ | ❌ คัดลอก-วาง |
| โหมดแปลภาษา | ✅ | ❌ | ❌ | ❌ |
| โอเพนซอร์ส | ✅ MIT | ❌ | ❌ | ✅ |
| ข้ามแพลตฟอร์ม | ✅ Win/Mac/Linux | ❌ Mac เท่านั้น | ❌ Windows เท่านั้น | ✅ |
| พจนานุกรมกำหนดเอง | ✅ | ❌ | ❌ | ❌ |
| โฮสต์เอง | ✅ BYOK | ❌ | ❌ | ✅ |

## คุณสมบัติ

- 🎙️ ปุ่มลัดบันทึกเสียงแบบทั่วระบบ — กดค้างเพื่อบันทึกหรือโหมดสลับ
- 💊 วิดเจ็ตแคปซูลลอยที่อยู่ด้านบนเสมอ
- 🗣️ ผู้ให้บริการ STT 6+ ราย: Deepgram, AssemblyAI, Whisper, Groq, GLM-ASR, SiliconFlow
- 🤖 ปรับแต่งข้อความผ่านหลาย LLM: OpenAI, DeepSeek, Claude, Gemini, Ollama และอื่น ๆ
- ⚡ แสดงผลแบบสตรีมมิง — ข้อความปรากฏขณะที่ LLM กำลังสร้าง
- ⌨️ จำลองแป้นพิมพ์หรือส่งออกผ่านคลิปบอร์ด
- 📝 ไฮไลท์ข้อความก่อนบันทึกเสียงเพื่อให้บริบทแก่ LLM
- 🌐 โหมดแปลภาษา: พูดภาษาหนึ่ง ส่งออกเป็นอีกภาษา (20+ ภาษา)
- 📖 พจนานุกรมกำหนดเองสำหรับคำศัพท์เฉพาะทาง
- 🔍 ตรวจจับแอปเพื่อปรับรูปแบบ
- 📜 ประวัติภายในเครื่องพร้อมการค้นหาแบบเต็มข้อความ
- 🌗 ธีมมืด / สว่าง / ตามระบบ
- 🚀 เริ่มอัตโนมัติเมื่อเข้าสู่ระบบ

> [!TIP]
> **การกำหนดค่าที่แนะนำเพื่อประสบการณ์ที่ดีที่สุด**
>
> | | ผู้ให้บริการ | Model |
> |---|---|---|
> | 🗣️ STT | Groq | `whisper-large-v3-turbo` |
> | 🤖 AI Polish | Google | `gemini-2.5-flash` |
>
> การผสมผสานนี้ให้การถอดเสียงที่รวดเร็วและแม่นยำพร้อมการปรับแต่งข้อความคุณภาพสูง — และทั้งสองมีแพ็กเกจฟรีที่ใจกว้าง

## ดาวน์โหลด

ดาวน์โหลดเวอร์ชันล่าสุดสำหรับแพลตฟอร์มของคุณ:

**[ดาวน์โหลดจาก Releases](https://github.com/tover0314-w/opentypeless/releases)**

| แพลตฟอร์ม | ไฟล์ |
|----------|------|
| Windows | ตัวติดตั้ง `.msi` |
| macOS (Apple Silicon) | `.dmg` |
| macOS (Intel) | `.dmg` |
| Linux | `.AppImage` / `.deb` |

## ข้อกำหนดเบื้องต้น

- [Node.js](https://nodejs.org/) 20+
- [Rust](https://rustup.rs/) (stable toolchain)
- การพึ่งพาเฉพาะแพลตฟอร์มสำหรับ Tauri: ดู [ข้อกำหนด Tauri](https://v2.tauri.app/start/prerequisites/)

## เริ่มต้นใช้งาน

```bash
# ติดตั้งการพึ่งพา
npm install

# ทำงานในโหมดพัฒนา
npm run tauri dev

# สร้างสำหรับ production
npm run tauri build
```

แอปพลิเคชันที่สร้างแล้วจะอยู่ใน `src-tauri/target/release/bundle/`

## การกำหนดค่า

การตั้งค่าทั้งหมดสามารถเข้าถึงได้จากแผงการตั้งค่าในแอป:

- **การจดจำเสียง** — เลือกผู้ให้บริการ STT และป้อน API key ของคุณ
- **AI Polish** — เลือกผู้ให้บริการ LLM, model และ API key
- **ทั่วไป** — ปุ่มลัด, โหมดส่งออก, ธีม, เริ่มอัตโนมัติ
- **พจนานุกรม** — เพิ่มคำศัพท์กำหนดเองเพื่อความแม่นยำที่ดีขึ้น
- **ฉาก** — เทมเพลต prompt สำหรับกรณีการใช้งานต่าง ๆ

API key ถูกเก็บไว้ภายในเครื่องผ่าน `tauri-plugin-store` ไม่มี key ใดถูกส่งไปยังเซิร์ฟเวอร์ OpenTypeless — คำขอ STT/LLM ทั้งหมดส่งตรงไปยังผู้ให้บริการที่คุณกำหนดค่า

### ตัวเลือก Cloud (Pro)

OpenTypeless ยังมีการสมัครสมาชิก Pro แบบเลือกได้ที่ให้โควตา STT และ LLM ที่จัดการให้ เพื่อที่คุณไม่ต้องมี API key ของตัวเอง นี่เป็นตัวเลือกทั้งหมด — แอปทำงานได้เต็มรูปแบบด้วย key ของคุณเอง

[เรียนรู้เพิ่มเติมเกี่ยวกับ Pro](https://www.opentypeless.com)

### BYOK (นำ Key มาเอง) เทียบกับ Cloud

| | โหมด BYOK | โหมด Cloud (Pro) |
|---|---|---|
| STT | API key ของคุณ (Deepgram, AssemblyAI ฯลฯ) | โควตาที่จัดการให้ (10 ชม./เดือน) |
| LLM | API key ของคุณ (OpenAI, DeepSeek ฯลฯ) | โควตาที่จัดการให้ (~5M token/เดือน) |
| การพึ่งพา cloud | ไม่มี — คำขอทั้งหมดส่งตรงไปยังผู้ให้บริการของคุณ | ต้องเชื่อมต่อกับ www.opentypeless.com |
| ค่าใช้จ่าย | ชำระโดยตรงกับผู้ให้บริการ | สมัครสมาชิก $4.99/เดือน |

คุณสมบัติหลักทั้งหมด — การบันทึกเสียง, การถอดเสียง, AI polish, ส่งออกแป้นพิมพ์/คลิปบอร์ด, พจนานุกรม, ประวัติ — ทำงานได้อย่างสมบูรณ์โดยไม่ต้องพึ่งเซิร์ฟเวอร์ OpenTypeless ในโหมด BYOK

### โฮสต์เอง / ไม่ใช้ Cloud

เพื่อใช้งาน OpenTypeless โดยไม่พึ่งพา cloud:

1. เลือกผู้ให้บริการ STT และ LLM ที่ไม่ใช่ Cloud ในการตั้งค่า
2. ป้อน API key ของคุณเอง
3. แค่นั้นเอง — ไม่ต้องมีบัญชีหรือการเชื่อมต่ออินเทอร์เน็ตกับ www.opentypeless.com

หากคุณต้องการชี้คุณสมบัติ cloud เสริมไปยัง backend ของคุณเอง ให้ตั้งค่าตัวแปรสภาพแวดล้อมเหล่านี้ก่อนสร้าง:

| ตัวแปร | ค่าเริ่มต้น | คำอธิบาย |
|---|---|---|
| `VITE_API_BASE_URL` | `https://www.opentypeless.com` | URL ฐาน API cloud สำหรับ frontend |
| `API_BASE_URL` | `https://www.opentypeless.com` | URL ฐาน API cloud สำหรับ Rust backend |

```bash
# ตัวอย่าง: สร้างด้วย backend กำหนดเอง
VITE_API_BASE_URL=https://my-server.example.com API_BASE_URL=https://my-server.example.com npm run tauri build
```

## สถาปัตยกรรม

**ไปป์ไลน์การไหลของข้อมูล:**

```
ไมโครโฟน → จับเสียง → ผู้ให้บริการ STT → ข้อความดิบ → LLM Polish → ส่งออกแป้นพิมพ์/คลิปบอร์ด
```

```
src/                  # React frontend (TypeScript)
├── components/       # คอมโพเนนต์ UI (การตั้งค่า, ประวัติ, Capsule ฯลฯ)
├── hooks/            # React hooks (การบันทึก, ธีม, เหตุการณ์ Tauri)
├── lib/              # ยูทิลิตี้ (API client, router, ค่าคงที่)
└── stores/           # การจัดการสถานะ Zustand

src-tauri/src/        # Rust backend
├── audio/            # จับเสียงผ่าน cpal
├── stt/              # ผู้ให้บริการ STT (Deepgram, AssemblyAI, รองรับ Whisper, Cloud)
├── llm/              # ผู้ให้บริการ LLM (รองรับ OpenAI, Cloud)
├── output/           # ส่งออกข้อความ (จำลองแป้นพิมพ์, วางคลิปบอร์ด)
├── storage/          # กำหนดค่า (tauri-plugin-store) + ประวัติ/พจนานุกรม (SQLite)
├── app_detector/     # ตรวจจับแอปพลิเคชันที่ใช้งานอยู่
├── pipeline.rs       # การประสานงาน บันทึก → STT → LLM → ส่งออก
└── lib.rs            # ตั้งค่าแอป Tauri, คำสั่ง, การจัดการปุ่มลัด
```

## แผนงาน

- [ ] ระบบปลั๊กอินสำหรับการผสานรวม STT/LLM กำหนดเอง
- [ ] ปรับปรุงความแม่นยำ STT หลายภาษาและรองรับสำเนียง
- [ ] คำสั่งเสียง (เช่น "ลบประโยคสุดท้าย")
- [ ] ปรับแต่งการผสมปุ่มลัด
- [ ] ปรับปรุงประสบการณ์การเริ่มต้นใช้งาน
- [ ] แอปคู่หูบนมือถือ

## คำถามที่พบบ่อย

**เสียงของฉันถูกส่งไปยัง cloud หรือไม่?**
ในโหมด BYOK เสียงจะส่งตรงไปยังผู้ให้บริการ STT ที่คุณเลือก (เช่น Groq, Deepgram) ไม่มีข้อมูลใดผ่านเซิร์ฟเวอร์ OpenTypeless ในโหมด Cloud (Pro) เสียงจะถูกส่งไปยัง proxy ที่จัดการให้สำหรับการถอดเสียง

**ฉันสามารถใช้งานออฟไลน์ได้หรือไม่?**
ด้วยผู้ให้บริการ STT ภายในเครื่อง (Whisper ผ่าน Ollama) และ LLM ภายในเครื่อง (Ollama) แอปทำงานได้ออฟไลน์ทั้งหมด ไม่ต้องเชื่อมต่ออินเทอร์เน็ต

**รองรับภาษาอะไรบ้าง?**
STT รองรับ 99+ ภาษา ขึ้นอยู่กับผู้ให้บริการ AI polish และการแปลรองรับ 20+ ภาษาเป้าหมาย

**แอปนี้ฟรีหรือไม่?**
ใช่ แอปทำงานได้เต็มรูปแบบด้วย API key ของคุณเอง (BYOK) การสมัครสมาชิก Cloud Pro ($4.99/เดือน) เป็นตัวเลือกเสริม

## ชุมชน

- 💬 [Discord](https://discord.gg/V6rRpJ4RGD) — แชท, รับความช่วยเหลือ, แบ่งปันความคิดเห็น
- 🗣️ [GitHub Discussions](https://github.com/tover0314-w/opentypeless/discussions) — เสนอคุณสมบัติ, ถาม-ตอบ
- 🐛 [Issue Tracker](https://github.com/tover0314-w/opentypeless/issues) — รายงานบั๊กและคำร้องขอคุณสมบัติ
- 📖 [คู่มือการมีส่วนร่วม](CONTRIBUTING.md) — การตั้งค่าสำหรับพัฒนาและแนวทาง
- 🔒 [นโยบายความปลอดภัย](SECURITY.md) — รายงานช่องโหว่อย่างรับผิดชอบ
- 🧭 [วิสัยทัศน์](VISION.md) — หลักการโครงการและทิศทางแผนงาน

## การมีส่วนร่วม

ยินดีต้อนรับทุกการมีส่วนร่วม! ดู [CONTRIBUTING.md](CONTRIBUTING.md) สำหรับการตั้งค่าสำหรับพัฒนาและแนวทาง

กำลังหาจุดเริ่มต้น? ดู issue ที่ติดป้าย [`good first issue`](https://github.com/tover0314-w/opentypeless/labels/good%20first%20issue)

## ประวัติ Star

<a href="https://star-history.com/#tover0314-w/opentypeless&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date" />
    <img alt="แผนภูมิประวัติ Star" src="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date" />
  </picture>
</a>

## สร้างด้วย Claude Code

โครงการทั้งหมดนี้สร้างขึ้นภายในวันเดียวโดยใช้ [Claude Code](https://claude.com/claude-code) — ตั้งแต่การออกแบบสถาปัตยกรรมจนถึงการพัฒนาเต็มรูปแบบ รวมถึง Tauri backend, React frontend, CI/CD pipeline และ README นี้

## สัญญาอนุญาต

[MIT](LICENSE)

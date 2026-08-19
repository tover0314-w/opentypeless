<p align="center">
  <a href="README.md">English</a> | <a href="README_zh.md">中文</a> | <a href="README_ja.md">日本語</a> | <a href="README_ko.md">한국어</a> | <a href="README_es.md">Español</a> | <a href="README_fr.md">Français</a> | <a href="README_de.md">Deutsch</a> | <a href="README_pt.md">Português</a> | <a href="README_ru.md">Русский</a> | <a href="README_ar.md">العربية</a> | <a href="README_hi.md">हिन्दी</a> | <a href="README_it.md">Italiano</a> | <a href="README_tr.md">Türkçe</a> | <a href="README_vi.md">Tiếng Việt</a> | <a href="README_th.md">ภาษาไทย</a> | <a href="README_id.md">Bahasa Indonesia</a> | <a href="README_pl.md">Polski</a> | <strong>Nederlands</strong>
</p>

<p align="center">
  <img src="src-tauri/icons/128x128@2x.png" width="128" height="128" alt="OpenTypeless Logo" />
</p>

<h1 align="center">OpenTypeless</h1>

<p align="center">
  Open-source AI-spraakinvoer voor desktop. Spreek natuurlijk, krijg verfijnde tekst in elke applicatie.
</p>

<p align="center">
  Of je nu e-mails schrijft, programmeert, chat of notities maakt — druk gewoon op een sneltoets,<br/>
  zeg wat je denkt, en OpenTypeless transcribeert en verfijnt je woorden met AI,<br/>
  en typt ze vervolgens direct in de applicatie die je gebruikt.
</p>

<p align="center">
  <a href="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml"><img src="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/releases"><img src="https://img.shields.io/github/v/release/tover0314-w/opentypeless?color=2ABBA7" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tover0314-w/opentypeless" alt="Licentie" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/stargazers"><img src="https://img.shields.io/github/stars/tover0314-w/opentypeless?style=social" alt="Sterren" /></a>
  <a href="https://discord.gg/V6rRpJ4RGD"><img src="https://img.shields.io/badge/Discord-Join%20us-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

<p align="center">
  <img src="docs/images/v1.1.49-app-context-showcase.jpg" width="820" alt="App-bewuste spraakinvoer met OpenTypeless in Gmail, Slack, Google Docs, Cursor, Zendesk en LinkedIn" />
</p>

<p align="center">
  <img src="docs/images/voice-flow-demo.gif" width="720" alt="OpenTypeless Demo" />
</p>

## Nieuw in v1.1.49

- **App-bewust schrijven** herkent de actieve toepassing lokaal en past structuur en toon aan voor e-mail, chat, documenten, issuetrackers, ontwikkeltools en meer.
- **Routering van spraakintenties** onderscheidt dicteren, geselecteerde tekst bewerken, vertalen, Ask Anything en ondersteunde spraakacties in het Engels, vereenvoudigd Chinees en traditioneel Chinees.
- **Meerdere sneltoetsen per workflow** laten je meer dan één combinatie toevoegen en rangschikken voor Dicteren, Ask Anything en Vertalen.
- **Wisselbare vertaaldoelen** maken het eenvoudig om tussen je gebruikelijke talen te schakelen zonder één vaste uitvoertaal.
- **Een uitgebreider lokaal woordenboek** ondersteunt correctieregels en het importeren en exporteren van het woordenboek.
- **Stijltoewijzingen per app** laten je de ingebouwde categorie overschrijven wanneer een toepassing een andere schrijfstijl nodig heeft.

App-detectie, toewijzingen, woordenboekitems en correctieregels worden lokaal opgeslagen. App-bewuste tekstverbetering stuurt alleen de interne appcategorie en goedgekeurde stijlmetadata naar het ingestelde LLM-pad; ruwe venstertitels en documentinhoud worden niet als appcontext verzonden of in de geschiedenis opgeslagen.

| App-bewuste AI-tekstverbetering | Lokaal woordenboek en correcties |
| --- | --- |
| <img src="docs/images/v1.1.49-app-aware-polish.jpg" width="420" alt="App-bewuste AI-tekstverbetering in OpenTypeless v1.1.49" /> | <img src="docs/images/v1.1.49-dictionary.jpg" width="420" alt="Lokaal woordenboek en correcties in OpenTypeless v1.1.49" /> |

<details>
<summary>Meer schermafbeeldingen</summary>

<p align="center">
  <img src="docs/images/app-main-light.png" width="720" alt="OpenTypeless Hoofdvenster" />
</p>

| Instellingen | Geschiedenis |
|---|---|
| <img src="docs/images/app-settings.png" width="360" /> | <img src="docs/images/app-history.png" width="360" /> |

</details>

---

## Waarom OpenTypeless?

| | OpenTypeless | macOS Dictatie | Windows Spraakinvoer | Whisper Desktop |
|---|---|---|---|---|
| AI-tekstverfijning | ✅ Meerdere LLM's | ❌ | ❌ | ❌ |
| Keuze STT-provider | ✅ 6+ providers | ❌ Alleen Apple | ❌ Alleen Microsoft | ❌ Alleen Whisper |
| Werkt in elke app | ✅ | ✅ | ✅ | ❌ Kopiëren-plakken |
| Vertaalmodus | ✅ | ❌ | ❌ | ❌ |
| Open source | ✅ MIT | ❌ | ❌ | ✅ |
| Cross-platform | ✅ Win/Mac/Linux | ❌ Alleen Mac | ❌ Alleen Windows | ✅ |
| Aangepast woordenboek | ✅ | ❌ | ❌ | ❌ |
| Zelf te hosten | ✅ BYOK | ❌ | ❌ | ✅ |

## Functies

- 🎙️ Globale sneltoets — ingedrukt houden of schakelen
- 💊 Zwevende capsule-widget, altijd bovenop
- 🗣️ 6+ STT-providers: Deepgram, AssemblyAI, Whisper, Groq, GLM-ASR, SiliconFlow
- 🤖 Tekstverfijning via meerdere LLM's: OpenAI, DeepSeek, Claude, Gemini, Ollama en meer
- ⚡ Streaming-uitvoer — tekst verschijnt terwijl het LLM genereert
- ⌨️ Toetsenbordsimulatie of klemborduitvoer
- 📝 Markeer tekst voor opname om het LLM context te geven
- 🌐 Vertaalmodus: spreek in één taal, uitvoer in een andere (20+ talen)
- 📖 Aangepast woordenboek voor vakspecifieke termen
- 🔍 App-detectie voor aanpassing van opmaak
- 📜 Lokale geschiedenis met zoeken in volledige tekst
- 🌗 Donker / licht / systeemthema
- 🚀 Automatisch starten bij aanmelding

> [!TIP]
> **Aanbevolen configuratie voor de beste ervaring**
>
> | | Provider | Model |
> |---|---|---|
> | 🗣️ STT | Groq | `whisper-large-v3-turbo` |
> | 🤖 AI-verfijning | Google | `gemini-2.5-flash` |
>
> Deze combinatie biedt snelle, nauwkeurige transcriptie met hoogwaardige tekstverfijning — en beide bieden royale gratis niveaus.

## Downloaden

Download de nieuwste versie voor jouw platform:

**[Downloaden van Releases](https://github.com/tover0314-w/opentypeless/releases)**

| Platform | Bestand |
|----------|---------|
| Windows | `.msi`-installatieprogramma |
| macOS (Apple Silicon) | `.dmg` |
| macOS (Intel) | `.dmg` |
| Linux | `.AppImage` / `.deb` |

## Vereisten

- [Node.js](https://nodejs.org/) 20+
- [Rust](https://rustup.rs/) (stabiele toolchain)
- Platformspecifieke afhankelijkheden voor Tauri: zie [Tauri Vereisten](https://v2.tauri.app/start/prerequisites/)

## Aan de slag

```bash
# Afhankelijkheden installeren
npm install

# Uitvoeren in ontwikkelingsmodus
npm run tauri dev

# Bouwen voor productie
npm run tauri build
```

De gebouwde applicatie bevindt zich in `src-tauri/target/release/bundle/`.

## Configuratie

Alle instellingen zijn toegankelijk vanuit het Instellingen-paneel in de app:

- **Spraakherkenning** — kies STT-provider en voer je API-sleutel in
- **AI-verfijning** — kies LLM-provider, model en API-sleutel
- **Algemeen** — sneltoets, uitvoermodus, thema, automatisch starten
- **Woordenboek** — voeg aangepaste termen toe voor betere transcriptienauwkeurigheid
- **Scènes** — promptsjablonen voor verschillende gebruiksscenario's

API-sleutels worden lokaal opgeslagen via `tauri-plugin-store`. Er worden geen sleutels naar OpenTypeless-servers gestuurd — alle STT/LLM-verzoeken gaan rechtstreeks naar de provider die je configureert.

### Cloud (Pro) Optie

OpenTypeless biedt ook een optioneel Pro-abonnement dat beheerd STT- en LLM-quotum biedt, zodat je geen eigen API-sleutels nodig hebt. Dit is geheel optioneel — de app is volledig functioneel met je eigen sleutels.

[Meer informatie over Pro](https://www.opentypeless.com)

### BYOK (Bring Your Own Key) vs Cloud

| | BYOK-modus | Cloud (Pro) modus |
|---|---|---|
| STT | Je eigen API-sleutel (Deepgram, AssemblyAI, enz.) | Beheerd quotum (10 uur/maand) |
| LLM | Je eigen API-sleutel (OpenAI, DeepSeek, enz.) | Beheerd quotum (~5M tokens/maand) |
| Cloudafhankelijkheid | Geen — alle verzoeken gaan rechtstreeks naar je provider | Vereist verbinding met www.opentypeless.com |
| Kosten | Betaal je provider rechtstreeks | $4,99/maand abonnement |

Alle kernfuncties — opname, transcriptie, AI-verfijning, toetsenbord-/klemborduitvoer, woordenboek, geschiedenis — werken volledig offline in BYOK-modus.

### Zelf hosten / Zonder cloud

Om OpenTypeless zonder cloudafhankelijkheid te gebruiken:

1. Kies een niet-Cloud STT- en LLM-provider in Instellingen
2. Voer je eigen API-sleutels in
3. Dat is alles — geen account of internetverbinding met www.opentypeless.com nodig

Als je de optionele cloudfuncties naar je eigen backend wilt verwijzen, stel dan deze omgevingsvariabelen in voor het bouwen:

| Variabele | Standaard | Beschrijving |
|---|---|---|
| `VITE_API_BASE_URL` | `https://www.opentypeless.com` | Frontend cloud API basis-URL |
| `API_BASE_URL` | `https://www.opentypeless.com` | Rust backend cloud API basis-URL |

```bash
# Voorbeeld: bouwen met een aangepaste backend
VITE_API_BASE_URL=https://my-server.example.com API_BASE_URL=https://my-server.example.com npm run tauri build
```

## Architectuur

**Gegevensstroom-pipeline:**

```
Microfoon → Audio-opname → STT-provider → Ruwe transcriptie → LLM-verfijning → Toetsenbord-/klemborduitvoer
```

```
src/                  # React-frontend (TypeScript)
├── components/       # UI-componenten (Instellingen, Geschiedenis, Capsule, enz.)
├── hooks/            # React-hooks (opname, thema, Tauri-events)
├── lib/              # Hulpprogramma's (API-client, router, constanten)
└── stores/           # Zustand-statusbeheer

src-tauri/src/        # Rust-backend
├── audio/            # Audio-opname via cpal
├── stt/              # STT-providers (Deepgram, AssemblyAI, Whisper-compatibel, Cloud)
├── llm/              # LLM-providers (OpenAI-compatibel, Cloud)
├── output/           # Tekstuitvoer (toetsenbordsimulatie, klembord plakken)
├── storage/          # Configuratie (tauri-plugin-store) + geschiedenis/woordenboek (SQLite)
├── app_detector/     # Actieve applicatie detecteren voor context
├── pipeline.rs       # Opname → STT → LLM → Uitvoer-orkestratie
└── lib.rs            # Tauri-app setup, commando's, sneltoetsafhandeling
```

## Routekaart

- [ ] Plugin-systeem voor aangepaste STT/LLM-integraties
- [ ] Verbeterde meertalige STT-nauwkeurigheid en dialectondersteuning
- [ ] Spraakopdrachten
- [ ] Aanpasbare sneltoetscombinaties
- [ ] Verbeterde onboarding-ervaring
- [ ] Mobiele companion-app

## FAQ

**Wordt mijn audio naar de cloud gestuurd?**
In BYOK-modus gaat audio rechtstreeks naar je gekozen STT-provider (bijv. Groq, Deepgram). Niets gaat via OpenTypeless-servers. In Cloud (Pro) modus wordt audio naar onze beheerde proxy gestuurd voor transcriptie.

**Kan ik het offline gebruiken?**
Met een lokale STT-provider (Whisper via Ollama) en een lokaal LLM (Ollama) werkt de app volledig offline. Geen internetverbinding nodig.

**Welke talen worden ondersteund?**
STT ondersteunt 99+ talen afhankelijk van de provider. AI-verfijning en vertaling ondersteunen 20+ doeltalen.

**Is de app gratis?**
Ja. De app is volledig functioneel met je eigen API-sleutels (BYOK). Het Cloud Pro-abonnement ($4,99/maand) is optioneel.

## Community

- 💬 [Discord](https://discord.gg/V6rRpJ4RGD) — Chat, hulp, feedback
- 🗣️ [GitHub Discussions](https://github.com/tover0314-w/opentypeless/discussions) — Functievoorstellen, vragen en antwoorden
- 🐛 [Issue Tracker](https://github.com/tover0314-w/opentypeless/issues) — Bugrapporten en functieverzoeken
- 📖 [Bijdragegids](CONTRIBUTING.md) — Ontwikkelingsopzet en richtlijnen
- 🔒 [Beveiligingsbeleid](SECURITY.md) — Kwetsbaarheden verantwoord melden
- 🧭 [Visie](VISION.md) — Projectprincipes en richting van de routekaart

## Bijdragen

Bijdragen zijn welkom! Zie [CONTRIBUTING.md](CONTRIBUTING.md) voor de ontwikkelingsopzet en richtlijnen.

Op zoek naar een beginpunt? Bekijk issues met het label [`good first issue`](https://github.com/tover0314-w/opentypeless/labels/good%20first%20issue).

## Star History

<a href="https://star-history.dera.page/#tover0314-w/opentypeless&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date" />
    <img alt="Star History Grafiek" src="https://star-history.dera.page/svg?repos=tover0314-w/opentypeless&type=Date" />
  </picture>
</a>

## Gebouwd met Claude Code in één dag

Dit gehele project is in één dag gebouwd met [Claude Code](https://claude.com/claude-code) — van architectuurontwerp tot volledige implementatie, inclusief Tauri-backend, React-frontend, CI/CD-pipeline en deze README.

## Licentie

[MIT](LICENSE)

<p align="center">
  <a href="README.md">English</a> | <a href="README_zh.md">中文</a> | <a href="README_ja.md">日本語</a> | <a href="README_ko.md">한국어</a> | <a href="README_es.md">Español</a> | <a href="README_fr.md">Français</a> | <a href="README_de.md">Deutsch</a> | <a href="README_pt.md">Português</a> | <a href="README_ru.md">Русский</a> | <a href="README_ar.md">العربية</a> | <a href="README_hi.md">हिन्दी</a> | <a href="README_it.md">Italiano</a> | <a href="README_tr.md">Türkçe</a> | <a href="README_vi.md">Tiếng Việt</a> | <a href="README_th.md">ภาษาไทย</a> | <a href="README_id.md">Bahasa Indonesia</a> | <strong>Polski</strong> | <a href="README_nl.md">Nederlands</a>
</p>

<p align="center">
  <img src="src-tauri/icons/128x128@2x.png" width="128" height="128" alt="Logo OpenTypeless" />
</p>

<h1 align="center">OpenTypeless</h1>

<p align="center">
  Otwarte oprogramowanie do wprowadzania tekstu glosem AI na komputerze. Mow naturalnie, otrzymuj dopracowany tekst w dowolnej aplikacji.
</p>

<p align="center">
  Niezaleznie czy piszesz e-maile, programujesz, rozmawiasz na czacie czy robisz notatki — po prostu nacisnij skrot klawiszowy,<br/>
  powiedz co myslisz, a OpenTypeless transkrybuje i dopracuje Twoje slowa za pomoca AI,<br/>
  a nastepnie wpisze je bezposrednio do aplikacji, ktorej uzywasz.
</p>

<p align="center">
  <a href="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml"><img src="https://github.com/tover0314-w/opentypeless/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/releases"><img src="https://img.shields.io/github/v/release/tover0314-w/opentypeless?color=2ABBA7" alt="Wydanie" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tover0314-w/opentypeless" alt="Licencja" /></a>
  <a href="https://github.com/tover0314-w/opentypeless/stargazers"><img src="https://img.shields.io/github/stars/tover0314-w/opentypeless?style=social" alt="Gwiazdki" /></a>
  <a href="https://discord.gg/V6rRpJ4RGD"><img src="https://img.shields.io/badge/Discord-Dolacz-5865F2?logo=discord&logoColor=white" alt="Discord" /></a>
</p>

<p align="center">
  <img src="docs/images/v1.1.49-app-context-showcase.jpg" width="820" alt="Wprowadzanie głosowe OpenTypeless dopasowane do Gmaila, Slacka, Google Docs, Cursora, Zendeska i LinkedIn" />
</p>

<p align="center">
  <img src="docs/images/voice-flow-demo.gif" width="720" alt="Demo OpenTypeless" />
</p>

## Najważniejsze funkcje

- **Pisanie z uwzględnieniem aplikacji** lokalnie wykrywa aktywną aplikację i dopasowuje strukturę oraz ton do poczty, czatu, dokumentów, narzędzi do śledzenia zgłoszeń, środowisk programistycznych i innych zastosowań.
- **Rozpoznawanie intencji głosowych** odróżnia dyktowanie, edycję zaznaczonego tekstu, tłumaczenie, Ask Anything i obsługiwane akcje głosowe w języku angielskim oraz chińskim uproszczonym i tradycyjnym.
- **Wiele skrótów dla każdego przepływu pracy** pozwala dodawać i porządkować więcej niż jedną kombinację dla Dyktowania, Ask Anything i Tłumaczenia.
- **Przełączane języki docelowe tłumaczenia** ułatwiają zmianę między używanymi językami bez ustawiania jednego stałego języka wyjściowego.
- **Rozbudowany lokalny słownik** obsługuje reguły korekty oraz import i eksport słownika.
- **Mapowanie stylu według aplikacji** pozwala zastąpić wbudowaną kategorię, gdy dana aplikacja wymaga innego stylu pisania.

Wykrywanie aplikacji, mapowania, słownik i reguły korekty są przechowywane lokalnie. Ulepszanie tekstu z uwzględnieniem aplikacji wysyła do skonfigurowanego LLM tylko wewnętrzną kategorię aplikacji i zatwierdzone metadane stylu; surowe tytuły okien i zawartość dokumentów nie są wysyłane jako kontekst ani zapisywane w historii.

| Ulepszanie AI z uwzględnieniem aplikacji | Lokalny słownik i korekty |
| --- | --- |
| <img src="docs/images/v1.1.49-app-aware-polish.jpg" width="420" alt="Ulepszanie AI z uwzględnieniem aplikacji w OpenTypeless v1.1.49" /> | <img src="docs/images/v1.1.49-dictionary.jpg" width="420" alt="Lokalny słownik i korekty w OpenTypeless v1.1.49" /> |

<details>
<summary>Wiecej zrzutow ekranu</summary>

<p align="center">
  <img src="docs/images/app-main-light.png" width="720" alt="Glowne okno OpenTypeless" />
</p>

| Ustawienia | Historia |
|---|---|
| <img src="docs/images/app-settings.png" width="360" /> | <img src="docs/images/app-history.png" width="360" /> |

</details>

---

## Dlaczego OpenTypeless?

| | OpenTypeless | macOS Dictation | Windows Voice Typing | Whisper Desktop |
|---|---|---|---|---|
| Dopracowanie tekstu AI | ✅ Wiele LLM | ❌ | ❌ | ❌ |
| Wybor dostawcy STT | ✅ 6+ dostawcow | ❌ Tylko Apple | ❌ Tylko Microsoft | ❌ Tylko Whisper |
| Dziala w kazdej aplikacji | ✅ | ✅ | ✅ | ❌ Kopiuj-wklej |
| Tryb tlumaczenia | ✅ | ❌ | ❌ | ❌ |
| Open source | ✅ MIT | ❌ | ❌ | ✅ |
| Wieloplatformowy | ✅ Win/Mac/Linux | ❌ Tylko Mac | ❌ Tylko Windows | ✅ |
| Slownik niestandardowy | ✅ | ❌ | ❌ | ❌ |
| Samodzielny hosting | ✅ BYOK | ❌ | ❌ | ✅ |

## Funkcje

- 🎙️ Globalny skrot klawiszowy nagrywania — przytrzymaj, aby nagrac lub tryb przelaczania
- 💊 Plywajacy widget kapsulki zawsze na wierzchu
- 🗣️ 6+ dostawcow STT: Deepgram, AssemblyAI, Whisper, Groq, GLM-ASR, SiliconFlow
- 🤖 Dopracowanie tekstu przez wiele LLM: OpenAI, DeepSeek, Claude, Gemini, Ollama i inne
- ⚡ Wyjscie strumieniowe — tekst pojawia sie w miarę generowania przez LLM
- ⌨️ Symulacja klawiatury lub wyjscie przez schowek
- 📝 Zaznacz tekst przed nagrywaniem, aby dostarczyc kontekst LLM
- 🌐 Tryb tlumaczenia: mow w jednym jezyku, wyjscie w innym (20+ jezykow)
- 📖 Slownik niestandardowy dla terminow specjalistycznych
- 🔍 Wykrywanie aplikacji w celu dostosowania formatowania
- 📜 Lokalna historia z wyszukiwaniem pelnotekstowym
- 🌗 Motyw ciemny / jasny / systemowy
- 🚀 Automatyczne uruchamianie przy logowaniu

> [!TIP]
> **Zalecana konfiguracja dla najlepszego doswiadczenia**
>
> | | Dostawca | Model |
> |---|---|---|
> | 🗣️ STT | Groq | `whisper-large-v3-turbo` |
> | 🤖 AI Polish | Google | `gemini-2.5-flash` |
>
> Ta kombinacja zapewnia szybka, dokladna transkrypcje z wysokiej jakosci dopracowaniem tekstu — a obaj dostawcy oferuja hojne darmowe plany.

## Pobieranie

Pobierz najnowsza wersje dla swojej platformy:

**[Pobierz z Releases](https://github.com/tover0314-w/opentypeless/releases)**

| Platforma | Plik |
|----------|------|
| Windows | Instalator `.msi` |
| macOS (Apple Silicon) | `.dmg` |
| macOS (Intel) | `.dmg` |
| Linux | `.AppImage` / `.deb` |

## Wymagania wstepne

- [Node.js](https://nodejs.org/) 20+
- [Rust](https://rustup.rs/) (stable toolchain)
- Zaleznosci specyficzne dla platformy dla Tauri: zobacz [Wymagania Tauri](https://v2.tauri.app/start/prerequisites/)

## Pierwsze kroki

```bash
# Zainstaluj zaleznosci
npm install

# Uruchom w trybie deweloperskim
npm run tauri dev

# Zbuduj dla production
npm run tauri build
```

Zbudowana aplikacja bedzie w `src-tauri/target/release/bundle/`.

## Konfiguracja

Wszystkie ustawienia sa dostepne z panelu Ustawien w aplikacji:

- **Rozpoznawanie mowy** — wybierz dostawce STT i wprowadz swoj API key
- **AI Polish** — wybierz dostawce LLM, model i API key
- **Ogolne** — skrot klawiszowy, tryb wyjscia, motyw, automatyczne uruchamianie
- **Slownik** — dodaj niestandardowe terminy dla lepszej dokladnosci transkrypcji
- **Sceny** — szablony promptow dla roznych przypadkow uzycia

Klucze API sa przechowywane lokalnie za pomoca `tauri-plugin-store`. Zadne klucze nie sa wysylane do serwerow OpenTypeless — wszystkie zadania STT/LLM ida bezposrednio do skonfigurowanego dostawcy.

### Opcja Cloud (Pro)

OpenTypeless oferuje rowniez opcjonalna subskrypcje Pro, ktora zapewnia zarzadzane limity STT i LLM, wiec nie potrzebujesz wlasnych kluczy API. Jest to calkowicie opcjonalne — aplikacja jest w pelni funkcjonalna z wlasnymi kluczami.

[Dowiedz sie wiecej o Pro](https://www.opentypeless.com)

### BYOK (Przynies Wlasny Klucz) vs Cloud

| | Tryb BYOK | Tryb Cloud (Pro) |
|---|---|---|
| STT | Twoj wlasny API key (Deepgram, AssemblyAI itp.) | Zarzadzany limit (10 godz./miesiac) |
| LLM | Twoj wlasny API key (OpenAI, DeepSeek itp.) | Zarzadzany limit (~5M tokenow/miesiac) |
| Zaleznosc od chmury | Brak — wszystkie zadania ida bezposrednio do Twojego dostawcy | Wymaga polaczenia z www.opentypeless.com |
| Koszt | Plac bezposrednio dostawcy | Subskrypcja $4.99/miesiac |

Wszystkie podstawowe funkcje — nagrywanie, transkrypcja, AI polish, wyjscie klawiatury/schowka, slownik, historia — dzialaja calkowicie niezaleznie od serwerow OpenTypeless w trybie BYOK.

### Samodzielny hosting / Bez chmury

Aby uruchomic OpenTypeless bez zaleznosci od chmury:

1. Wybierz dowolnego dostawce STT i LLM innego niz Cloud w Ustawieniach
2. Wprowadz wlasne klucze API
3. To wszystko — nie potrzebujesz konta ani polaczenia internetowego z www.opentypeless.com

Jesli chcesz przekierowac opcjonalne funkcje chmurowe na wlasny backend, ustaw te zmienne srodowiskowe przed budowaniem:

| Zmienna | Domyslna | Opis |
|---|---|---|
| `VITE_API_BASE_URL` | `https://www.opentypeless.com` | Bazowy URL API chmury dla frontendu |
| `API_BASE_URL` | `https://www.opentypeless.com` | Bazowy URL API chmury dla Rust backendu |

```bash
# Przyklad: budowanie z niestandardowym backendem
VITE_API_BASE_URL=https://my-server.example.com API_BASE_URL=https://my-server.example.com npm run tauri build
```

## Architektura

**Potok przeplywu danych:**

```
Mikrofon → Przechwytywanie dzwieku → Dostawca STT → Surowa transkrypcja → LLM Polish → Wyjscie klawiatury/schowka
```

```
src/                  # React frontend (TypeScript)
├── components/       # Komponenty UI (Ustawienia, Historia, Capsule itp.)
├── hooks/            # React hooks (nagrywanie, motyw, zdarzenia Tauri)
├── lib/              # Narzedzia (API client, router, stale)
└── stores/           # Zarzadzanie stanem Zustand

src-tauri/src/        # Rust backend
├── audio/            # Przechwytywanie dzwieku przez cpal
├── stt/              # Dostawcy STT (Deepgram, AssemblyAI, kompatybilny z Whisper, Cloud)
├── llm/              # Dostawcy LLM (kompatybilny z OpenAI, Cloud)
├── output/           # Wyjscie tekstu (symulacja klawiatury, wklejanie ze schowka)
├── storage/          # Konfiguracja (tauri-plugin-store) + historia/slownik (SQLite)
├── app_detector/     # Wykrywanie aktywnej aplikacji dla kontekstu
├── pipeline.rs       # Orkiestracja Nagrywanie → STT → LLM → Wyjscie
└── lib.rs            # Konfiguracja aplikacji Tauri, komendy, obsluga skrotow klawiszowych
```

## Plan rozwoju

- [ ] System pluginow dla niestandardowych integracji STT/LLM
- [ ] Poprawiona dokladnosc STT dla wielu jezykow i wsparcie dialektow
- [ ] Komendy glosowe (np. "usun ostatnie zdanie")
- [ ] Konfigurowalne kombinacje skrotow klawiszowych
- [ ] Ulepszone doswiadczenie wdrazania
- [ ] Aplikacja towarzyszaca na urzadzenia mobilne

## FAQ

**Czy moj dzwiek jest wysylany do chmury?**
W trybie BYOK dzwiek trafia bezposrednio do wybranego dostawcy STT (np. Groq, Deepgram). Nic nie przechodzi przez serwery OpenTypeless. W trybie Cloud (Pro) dzwiek jest wysylany do naszego zarzadzanego proxy w celu transkrypcji.

**Czy moge uzywac offline?**
Z lokalnym dostawca STT (Whisper przez Ollama) i lokalnym LLM (Ollama) aplikacja dziala calkowicie offline. Nie jest potrzebne polaczenie internetowe.

**Jakie jezyki sa obslugiwane?**
STT obsluguje 99+ jezykow w zaleznosci od dostawcy. AI polish i tlumaczenie obsluguja 20+ jezykow docelowych.

**Czy aplikacja jest darmowa?**
Tak. Aplikacja jest w pelni funkcjonalna z wlasnymi kluczami API (BYOK). Subskrypcja Cloud Pro ($4.99/miesiac) jest opcjonalna.

## Spolecznosc

- 💬 [Discord](https://discord.gg/V6rRpJ4RGD) — Czat, pomoc, udostepnianie opinii
- 🗣️ [GitHub Discussions](https://github.com/tover0314-w/opentypeless/discussions) — Propozycje funkcji, pytania i odpowiedzi
- 🐛 [Issue Tracker](https://github.com/tover0314-w/opentypeless/issues) — Zgloszenia bledow i prosby o funkcje
- 📖 [Przewodnik kontrybuowania](CONTRIBUTING.md) — Konfiguracja srodowiska deweloperskiego i wytyczne
- 🔒 [Polityka bezpieczenstwa](SECURITY.md) — Odpowiedzialne zglaszanie podatnosci
- 🧭 [Wizja](VISION.md) — Zasady projektu i kierunek rozwoju

## Kontrybuowanie

Kontrybuacje sa mile widziane! Zobacz [CONTRIBUTING.md](CONTRIBUTING.md) po informacje o konfiguracji srodowiska deweloperskiego i wytycznych.

Szukasz od czego zaczac? Sprawdz zadania oznaczone [`good first issue`](https://github.com/tover0314-w/opentypeless/labels/good%20first%20issue).

## Historia gwiazdek

<a href="https://star-history.com/#tover0314-w/opentypeless&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date" />
    <img alt="Wykres historii gwiazdek" src="https://api.star-history.com/svg?repos=tover0314-w/opentypeless&type=Date" />
  </picture>
</a>

## Zbudowane z Claude Code

Caly ten projekt zostal zbudowany w ciagu jednego dnia przy uzyciu [Claude Code](https://claude.com/claude-code) — od projektowania architektury po pelna implementacje, w tym backend Tauri, frontend React, pipeline CI/CD i ten README.

## Licencja

[MIT](LICENSE)

# Android visual system v1 — implementation and acceptance

Date: 2026-08-11
Scope: visual hierarchy and companion-app information architecture only

## Implemented

- A status-first launcher replaces the technical settings form as the app entry point.
- Home reports the configured or last successfully used recognition route, its truthful data
  boundary, microphone/keyboard readiness, and the real-device voice-check state.
- Settings now starts with route, privacy, and readiness, then discloses voice/models, input,
  personalization, privacy, and advanced connections by user intent.
- Home, History, Dictionary, and Settings share a stable bottom navigation. Detailed settings and
  Voice Lab use a back header instead of a duplicate bottom bar.
- The companion app and IME share a fixed graphite/off-white/periwinkle visual token set in light
  and dark themes, independent of OEM dynamic-accent changes.
- The keyboard retains its verified two-row interaction model. The hold-to-talk Space pill is the
  only idle primary action; mode and long dictation remain secondary until active.
- Personalization counts on the Settings summary use bounded SQL counts and decrypt no user text.

## Deliberately unchanged

- Recognition routing, ASR clients, offline models, punctuation, personalization, draft recovery,
  and editor commit behavior.
- Existing detailed provider and credential forms. They remain one level below the new Settings
  home and keep `FLAG_SECURE`.
- The Java/View implementation. A Kotlin/Compose migration is not part of this visual preview.

## Verification

- JVM: 254 tests, 0 failures, 0 errors, 0 skipped.
- API 36 emulator: 30 instrumentation tests, 0 failures, 0 errors; one real-model download test is
  intentionally skipped without its opt-in environment flag.
- `lintDebug` and `lintRelease`: 0 issues.
- Debug and release APK assembly: successful.
- Manual visual matrix: dark theme, English and Simplified Chinese, 411 dp at font scale 1.0, and
  320 dp at font scale 2.0. The keyboard remains two rows, all controls remain visible, and the
  bottom navigation auto-sizes instead of clipping.

## Deferred to a later iteration

- Dedicated native detail pages for each Settings category.
- A richer Home activity timeline and local performance dashboard.
- A reusable icon set and motion system.
- Tablet/landscape-specific companion layouts and the Xiaomi 15 physical navigation-mode matrix.
- Any language/framework migration or recognition-model change.

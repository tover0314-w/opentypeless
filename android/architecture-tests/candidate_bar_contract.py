#!/usr/bin/env python3
"""KBD-007/RIM-005 bounded candidate bar and generation-bound Rime wiring."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


CANDIDATE_ROOT = Path(
    "app/src/main/java/com/opentypeless/android/keyboard/candidate"
)
MODEL = CANDIDATE_ROOT / "CandidatePage.java"
BAR = CANDIDATE_ROOT / "KeyboardCandidateBar.java"
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
MODEL_TEST = Path(
    "app/src/test/java/com/opentypeless/android/keyboard/candidate/CandidatePageTest.java"
)
VIEW_TEST = Path(
    "app/src/androidTest/java/com/opentypeless/android/keyboard/candidate/"
    "KeyboardCandidateBarInstrumentedTest.java"
)
WRITER = re.compile(
    r"\.\s*(?:commitText|setComposingText|finishComposingText|"
    r"deleteSurroundingText(?:InCodePoints)?|sendKeyEvent|setSelection)\s*\("
)


@dataclass(frozen=True)
class Violation:
    rule: str
    detail: str


def _read(root: Path, relative: Path, rule: str, violations: list[Violation]) -> str:
    path = root / relative
    if not path.is_file() or path.is_symlink():
        violations.append(Violation(rule, str(relative)))
        return ""
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeError:
        violations.append(Violation(rule, f"invalid UTF-8: {relative}"))
        return ""


def _compact(value: str) -> str:
    return re.sub(r"\s+", "", value)


def inspect_android(android_root: Path) -> tuple[Violation, ...]:
    root = android_root.resolve()
    violations: list[Violation] = []
    expected_files = {"CandidatePage.java", "KeyboardCandidateBar.java"}
    candidate_dir = root / CANDIDATE_ROOT
    actual_files = (
        {path.name for path in candidate_dir.glob("*.java")}
        if candidate_dir.is_dir() and not candidate_dir.is_symlink()
        else set()
    )
    if actual_files != expected_files:
        violations.append(Violation(
            "KBD007_SOURCE_SET",
            f"candidate source set drifted: {sorted(actual_files)}",
        ))

    model = _read(root, MODEL, "KBD007_MODEL_SOURCE", violations)
    bar = _read(root, BAR, "KBD007_BAR_SOURCE", violations)
    service = _read(root, SERVICE, "KBD007_SERVICE_SOURCE", violations)
    model_test = _read(root, MODEL_TEST, "KBD007_MODEL_TEST", violations)
    view_test = _read(root, VIEW_TEST, "KBD007_VIEW_TEST", violations)

    forbidden = (
        "InputConnection", "EditorTransaction", "EditorSessionManager",
        "CompositionCoordinator", "java.lang.reflect", "java.lang.invoke",
        "dalvik.system", "sun.misc.Unsafe", "System.loadLibrary", " native ",
        "java.net.", "okhttp", "SharedPreferences", "Intent(", "android.database",
    )
    for name, source in (("model", model), ("bar", bar)):
        if any(token in source for token in forbidden) or WRITER.search(source):
            violations.append(Violation(
                "KBD007_CAPABILITY_BOUNDARY",
                f"{name} must remain free of editor/native/network/storage authority",
            ))

    model_compact = _compact(model)
    model_tokens = (
        "publicstaticfinalintMAXIMUM_CANDIDATES=16;",
        "publicstaticfinalintMAXIMUM_PAGES=128;",
        "publicstaticfinalintMAXIMUM_TEXT_CODE_POINTS=256;",
        "recordSelection(StringproducerId,longgeneration,longpageRevision,"
        "intpageIndex,intcandidateIndex,StringcandidateId,StringexpectedText)",
        "recordPageRequest(",
        "Directiondirection",
        "List.copyOf(items)",
        "candidateidsmustbeuniquewithinapage",
        "expectedText=<redacted>",
    )
    if any(token not in model_compact for token in model_tokens):
        violations.append(Violation(
            "KBD007_MODEL_CONTRACT",
            "candidate model must stay bounded, immutable, revision-bound and redacted",
        ))
    if "catch(" in model_compact or model.count("listener"):
        violations.append(Violation(
            "KBD007_MODEL_CONTRACT",
            "candidate model must not swallow failures or own callbacks",
        ))

    bar_compact = _compact(bar)
    bar_tokens = (
        "MINIMUM_TOUCH_TARGET_DP=48",
        "newHorizontalScrollView(context)",
        "interfaceListener{",
        "onCandidateSelected(CandidatePage.Selectionselection)",
        "onPageRequested(CandidatePage.PageRequestrequest)",
        "renderedPage!=page",
        "if(!visible)clear()",
        "candidateRow.removeAllViews()",
        "root.setVisibility(View.GONE)",
        "listener.onCandidateSelected(page.selection(candidateIndex))",
        "listener.onPageRequested(page.pageRequest(direction))",
    )
    if any(token not in bar_compact for token in bar_tokens):
        violations.append(Violation(
            "KBD007_VIEW_CONTRACT",
            "candidate bar must keep horizontal, 48dp, stale-safe and destructive privacy behavior",
        ))
    if "catch(" in bar_compact:
        violations.append(Violation(
            "KBD007_VIEW_CONTRACT",
            "candidate bar must not swallow callback or validation failures",
        ))

    service_tokens = (
        "new KeyboardCandidateBar(",
        "compositionStage.addView(keyboardCandidateBar.root(), matchWrap())",
        "shellFrame.attachComposition(compositionStage, matchWrap())",
        "keyboardCandidateBar.clear()",
        "keyboardCandidateBar.setPlaintextVisible(!sensitiveField)",
        "keyboardCandidateBar.setPlaintextVisible(false)",
        "keyboardCandidateBar.setInteractionEnabled(editorEnabled)",
        "routeRimeCandidateSelection(selection)",
        "routeRimeCandidatePage(request)",
        "lease.pendingSelection = selection",
        "lease.pendingPageRequest = request",
        "rejectUnboundCandidateEvent()",
        "an unbound event never writes",
    )
    if (
        any(token not in service for token in service_tokens)
        or service.count("new KeyboardCandidateBar(") != 1
        or service.count("routeRimeCandidateSelection(selection);") != 1
        or service.count("routeRimeCandidatePage(request);") != 1
    ):
        violations.append(Violation(
            "KBD007_SERVICE_WIRING",
            "Route-A must bind one candidate bar to exact Rime identity and lifecycle policy",
        ))

    model_test_tokens = (
        "latinAndRimeUseTheSameStableSelectionContract",
        "pagingCarriesOriginalGenerationRevisionAndDirection",
        "invalidIdentityBoundsAndControlTextFailClosed",
        "duplicateIdsAndOversizedPagesFailClosed",
        "diagnosticsRedactCandidateText",
    )
    if any(token not in model_test for token in model_test_tokens):
        violations.append(Violation(
            "KBD007_MODEL_TEST",
            "model tests must cover reuse, paging identity, bounds and redaction",
        ))

    view_test_tokens = (
        "horizontalBarRendersNumberedAccessibleCandidatesAndStableSelection",
        "staleDetachedButtonCannotSelectAfterReplacementOrClear",
        "sensitivePolicyDestructivelyClearsPlaintextAndRejectsNewPage",
        "disabledInteractionAndFortyEightDpTargetsRemainFailClosed",
        "latinAndRimePagesReuseTheSameViewWithoutRetainingOldText",
    )
    if any(token not in view_test for token in view_test_tokens):
        violations.append(Violation(
            "KBD007_VIEW_TEST",
            "View tests must cover accessibility, stale callbacks, privacy, targets and reuse",
        ))
    return tuple(violations)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--android-root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    args = parser.parse_args()
    violations = inspect_android(args.android_root)
    if violations:
        for item in violations:
            print(f"{item.rule}: {item.detail}", file=sys.stderr)
        return 1
    print("KBD-007 candidate bar source boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

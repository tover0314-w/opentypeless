#!/usr/bin/env python3
"""RIM-001/RIM-004/RIM-005/RIM-006 bounded engine and configuration gate."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


RIME_ROOT = Path("app/src/main/java/com/opentypeless/android/keyboard/rime")
ENGINE = RIME_ROOT / "RimeInputEngine.java"
SNAPSHOT = RIME_ROOT / "RimeEngineSnapshot.java"
NATIVE = RIME_ROOT / "NativeRimeInputEngine.java"
CONTROLLER = RIME_ROOT / "RimeInputController.java"
CONFIG = RIME_ROOT / "RimeRuntimeConfig.java"
PENDING_SYMBOLS = RIME_ROOT / "PendingRimeSymbols.java"
TEST = Path(
    "app/src/test/java/com/opentypeless/android/keyboard/rime/"
    "RimeInputEngineContractTest.java"
)
NATIVE_TEST = Path(
    "app/src/test/java/com/opentypeless/android/keyboard/rime/"
    "NativeRimeInputEngineTest.java"
)
CONTROLLER_TEST = Path(
    "app/src/test/java/com/opentypeless/android/keyboard/rime/"
    "RimeInputControllerTest.java"
)
CONFIG_TEST = Path(
    "app/src/test/java/com/opentypeless/android/keyboard/rime/"
    "RimeRuntimeConfigTest.java"
)
SERVICE = Path("app/src/main/java/com/opentypeless/android/ime/OpenTypelessImeService.java")
PREFERENCES = Path(
    "app/src/main/java/com/opentypeless/android/rime/importer/"
    "RimeRuntimePreferences.java"
)
ACTIVITY = Path("app/src/main/java/com/opentypeless/android/RimeResourceActivity.java")
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
    expected_files = {
        "RimeInputEngine.java", "RimeEngineSnapshot.java",
        "NativeRimeInputEngine.java", "RimeInputController.java",
        "RimeRuntimeConfig.java", "PendingRimeSymbols.java",
    }
    source_dir = root / RIME_ROOT
    actual_files = (
        {path.name for path in source_dir.glob("*.java")}
        if source_dir.is_dir() and not source_dir.is_symlink()
        else set()
    )
    if actual_files != expected_files:
        violations.append(Violation(
            "RIM001_SOURCE_SET",
            f"Rime contract source set drifted: {sorted(actual_files)}",
        ))

    engine = _read(root, ENGINE, "RIM001_ENGINE_SOURCE", violations)
    snapshot = _read(root, SNAPSHOT, "RIM001_SNAPSHOT_SOURCE", violations)
    native = _read(root, NATIVE, "RIM004_NATIVE_SOURCE", violations)
    controller = _read(root, CONTROLLER, "RIM004_CONTROLLER_SOURCE", violations)
    config = _read(root, CONFIG, "RIM006_CONFIG_SOURCE", violations)
    pending_symbols = _read(
        root, PENDING_SYMBOLS, "KBD015_RIME_SYMBOL_SOURCE", violations
    )
    test = _read(root, TEST, "RIM001_CONTRACT_TEST", violations)
    native_test = _read(root, NATIVE_TEST, "RIM004_NATIVE_TEST", violations)
    controller_test = _read(root, CONTROLLER_TEST, "RIM004_CONTROLLER_TEST", violations)
    config_test = _read(root, CONFIG_TEST, "RIM006_CONFIG_TEST", violations)
    service = _read(root, SERVICE, "RIM001_SERVICE_SOURCE", violations)
    preferences = _read(root, PREFERENCES, "RIM006_PREFERENCES_SOURCE", violations)
    activity = _read(root, ACTIVITY, "RIM006_ACTIVITY_SOURCE", violations)

    forbidden = (
        "InputConnection", "EditorTransaction", "EditorOperation",
        "EditorSessionManager", "CompositionCoordinator", "RimeAdapter",
        "System.loadLibrary", " native ", "java.lang.reflect", "java.lang.invoke",
        "dalvik.system", "sun.misc.Unsafe", "java.net.", "okhttp", "File(",
        "Path.of", "SharedPreferences", "android.database", "SQLite",
    )
    for name, source in (
        ("engine", engine),
        ("snapshot", snapshot),
        ("config", config),
        ("pending_symbols", pending_symbols),
    ):
        android_reference = re.search(
            r"(?m)^\s*import\s+android\.|(?<!opentypeless\.)android\.", source
        )
        if (
            any(token in source for token in forbidden)
            or android_reference
            or WRITER.search(source)
        ):
            violations.append(Violation(
                "RIM001_CAPABILITY_BOUNDARY",
                f"{name} must not own Android/editor/JNI/network/storage authority",
            ))
        if name != "pending_symbols" and ("catch (" in source or "catch(" in source):
            violations.append(Violation(
                "RIM001_FAILURE_BOUNDARY",
                f"{name} must expose stable failures instead of swallowing exceptions",
            ))

    implementation_forbidden = (
        "InputConnection", "EditorTransaction", "EditorOperation",
        "EditorSessionManager", "CompositionCoordinator", "android.content.Context",
        "SharedPreferences", "java.net.", "okhttp", "dalvik.system",
        "java.lang.reflect", "java.lang.invoke", "sun.misc.Unsafe",
    )
    for name, source in (("native", native), ("controller", controller)):
        if any(token in source for token in implementation_forbidden) or WRITER.search(source):
            violations.append(Violation(
                "RIM004_CAPABILITY_BOUNDARY",
                f"{name} must not own editor, Android UI, network or dynamic-code authority",
            ))
    native_compact = _compact(native)
    for token in (
        "publicfinalclassNativeRimeInputEngineimplementsRimeInputEngine",
        "RimeAdapter.open(sharedDirectory,userDirectory,schemaId)",
        "session.setOption(option,runtimeConfig.optionValue(option))",
        "request.learningMode()!=LearningMode.ENABLED",
        "caseBACKSPACE", "caseESCAPE", "caseENTER",
        "returnrejected(FailureKind.POLICY_DENIED)",
        "CANDIDATES_PER_PAGE=5",
        "StringselectCandidate(intindex);",
        "defaultStringtakePendingCommit(){returnnull;}",
        "activePageIndex*CANDIDATES_PER_PAGE",
        "session.selectCandidate(nativeIndex)",
        "newCandidatePage(PRODUCER_ID,generation,revision,pageIndex,pageCount,items)",
    ):
        if token not in native_compact:
            violations.append(Violation("RIM004_005_NATIVE_CONTRACT", token))
    if native_compact.count("session.selectCandidate(") != 1:
        violations.append(Violation(
            "RIM005_NATIVE_ONE_SHOT",
            "one exact candidate selection may invoke the native adapter only once",
        ))
    if native_compact.count("session.setOption(") != 1:
        violations.append(Violation(
            "RIM006_NATIVE_OPTION_BOUNDARY",
            "the closed configuration loop must be the only native option call site",
        ))
    controller_compact = _compact(controller)
    for token in (
        "newArrayBlockingQueue<>(MAXIMUM_PENDING_COMMANDS)",
        "enumEnqueueResult{QUEUED,BACKPRESSURE,CLOSED}",
        "if(closed.get())return;",
        "selectCandidate(CandidatePage.Selectionselection)",
        "requestCandidatePage(CandidatePage.PageRequestrequest)",
        "listener.onResult(editorGeneration,coordinationGeneration,result)",
    ):
        if token not in controller_compact:
            violations.append(Violation("RIM004_CONTROLLER_CONTRACT", token))

    engine_compact = _compact(engine)
    engine_tokens = (
        "publicinterfaceRimeInputEngineextendsAutoCloseable",
        'StringPRODUCER_ID="rime";',
        "enumLearningMode{DISABLED,ENABLED}",
        "enumFailureKind{CLOSED,INACTIVE,ALREADY_ACTIVE,"
        "STALE_EDITOR_GENERATION,STALE_COORDINATION_GENERATION,POLICY_DENIED,"
        "INVALID_OUTPUT,ENGINE_UNAVAILABLE,ENGINE_FAILURE}",
        "recordActivation(longeditorGeneration,longcoordinationGeneration,"
        "longinitialRevision,LearningModelearningMode)",
        "recordProcessRequest(longeditorGeneration,longcoordinationGeneration,Keykey)",
        "recordCandidateSelectionRequest(longeditorGeneration,"
        "CandidatePage.Selectionselection)",
        "recordCandidatePageRequest(longeditorGeneration,CandidatePage.PageRequestrequest)",
        "LifecycleResultactivate(Activationrequest);",
        "LifecycleResultdeactivate(Deactivationrequest);",
        "ProcessResultprocess(ProcessRequestrequest);",
        "ProcessResultselectCandidate(CandidateSelectionRequestrequest);",
        "ProcessResultrequestCandidatePage(CandidatePageRequestrequest);",
        "SnapshotResultsnapshot();",
        "voidclose();",
        "text=<redacted>",
        "codePoint=<redacted>",
    )
    if any(token not in engine_compact for token in engine_tokens):
        violations.append(Violation(
            "RIM001_ENGINE_CONTRACT",
            "engine lifecycle/process/snapshot/candidate surface must stay closed and redacted",
        ))

    snapshot_compact = _compact(snapshot)
    snapshot_tokens = (
        "MAXIMUM_TEXT_CODE_POINTS=256",
        "MAXIMUM_TEXT_UTF16_UNITS=512",
        "enumPhase{INACTIVE,ACTIVE}",
        "candidatePage.generation()!=coordinationGeneration",
        "candidatePage.pageRevision()!=revision",
        '!PRODUCER_ID.equals(candidatePage.producerId())',
        "Character.isISOControl(codePoint)||isBidiControl(codePoint)",
        "preedit=<redacted>",
    )
    if any(token not in snapshot_compact for token in snapshot_tokens):
        violations.append(Violation(
            "RIM001_SNAPSHOT_CONTRACT",
            "snapshot must remain bounded, identity-bound, inactive-safe and redacted",
        ))

    pending_symbols_compact = _compact(pending_symbols)
    pending_symbol_tokens = (
        "MAXIMUM_SYMBOLS=8;",
        "if(!isSingleSafeSymbol(symbol)||count()>=MAXIMUM_SYMBOLS)returnfalse",
        "RimeInputEngine.Key.printable(codePoint)",
        'case","->"，"',
        'case"."->"。"',
        "PendingRimeSymbols{count=",
        "<redacted>",
    )
    if any(token not in pending_symbols_compact for token in pending_symbol_tokens):
        violations.append(Violation(
            "KBD015_RIME_SYMBOL_BOUNDARY",
            "Rime symbol suffix must be scalar-bounded, punctuation-aware and redacted",
        ))

    test_tokens = (
        "interfaceExposesOnlyTheBoundedAdapterLifecycle",
        "deterministicFakeExercisesActivateProcessSnapshotCandidateAndDeactivate",
        "lifecycleAndRequestsRequirePositiveGenerationAndRimeOwnership",
        "keysAcceptUnicodeScalarsAndRejectControlsSurrogatesAndTextOnCommands",
        "snapshotRequiresCandidateGenerationRevisionAndProducerIdentity",
        "inactiveAndBoundedTextStatesFailClosed",
        "commitReadyRequiresOneMatchingGenerationAndRevision",
        "diagnosticsRedactPreeditKeyCandidateAndCommitText",
    )
    if any(token not in test for token in test_tokens):
        violations.append(Violation(
            "RIM001_CONTRACT_TEST",
            "JVM tests must cover the complete lifecycle, bounds, identity and redaction",
        ))

    if any(token not in service for token in (
        "routeTypingText(text)",
        "routeDeleteBackward()",
        "routeKeyboardEnter()",
        "routeRimeCandidateSelection(selection)",
        "routeRimeCandidatePage(request)",
        "editorSessionManager.setRimeComposition(",
        "editorSessionManager.finishRimeComposition(",
        "refreshRimeAvailability(editorEpoch, privacy)",
        "closeRimeComposition(false)",
    )) or (
        "newNativeRimeInputEngine(runtime.root(),config,rimeUserDataStore,"
        "runtime.deploymentId())"
    ) not in _compact(
        service
    ) or "RimeAdapter" in service:
        violations.append(Violation(
            "RIM004_RUNTIME_WIRING",
            "service must route bounded Rime keys and preedit through the manager, never JNI",
        ))
    service_compact = _compact(service)
    if service_compact.count("editorSessionManager.setRimeComposition(") != 3:
        violations.append(Violation(
            "RIM004_RUNTIME_WIRING",
            "preedit, candidate commit and Rime-to-Voice cancellation must be the only Rime composition write routes",
        ))
    for token in (
        "lease.pendingSelection=selection",
        "lease.pendingPageRequest=request",
        "commit.text().equals(selection.expectedText())",
        "lease.candidatePage.selection(selection.candidateIndex()).equals(selection)",
        "lease.pendingKeyCommands!=0",
        "suppressRimeCandidatePage()",
        "if(keyboardCandidateBar!=null)keyboardCandidateBar.clear()",
        "lease.controller.warmUp()",
    ):
        if token not in service_compact:
            violations.append(Violation("RIM005_RUNTIME_WIRING", token))

    for token in (
        "finalPendingRimeSymbolspendingSymbols=newPendingRimeSymbols()",
        "!lease.pendingSymbols.offer(normalized)",
        "continuePendingRimeSymbols(lease)",
        "StringeditorText=lease.pendingSymbols.appendTo(commit.text())",
        "editorSessionManager.setRimeComposition(this,lease.editorSnapshot,editorText,commit.revision())",
    ):
        if token not in service_compact:
            violations.append(Violation("KBD015_RIME_SYMBOL_WIRING", token))
    if service_compact.count("suppressRimeCandidatePage()") != 3:
        violations.append(Violation(
            "RIM005_RUNTIME_WIRING",
            "personal Rime candidate presentation must stay suppressed at both update sites",
        ))
    expected_caret = service.find("lease.expectedCaret = (int) caret;")
    composition_write = service.find("editorSessionManager.setRimeComposition(")
    if expected_caret < 0 or composition_write < 0 or expected_caret > composition_write:
        violations.append(Violation(
            "RIM004_SYNC_SELECTION_GUARD",
            "the expected Rime caret must be registered before the OEM composition write",
        ))
    for token in (
        "asciiAndBackspaceProduceMonotonicBoundedPreedit",
        "unsupportedUnicodeEnterAndUnboundCandidateFailClosed",
        "candidatePagesSelectExactAbsoluteIndexOnceAndRejectReplay",
        "fixedLengthNativeAutoCommitReturnsCommitAndCreatesRecoveryPoint",
        "staleCandidateIdentityNeverCallsNativeSelection",
        "staleGenerationAndCloseNeverCallNativeAgain",
    ):
        if token not in native_test:
            violations.append(Violation("RIM004_NATIVE_TEST", token))
    for token in (
        "workerPreservesOrderAndDeliversExactIdentity",
        "boundedQueueRejectsFloodAndCloseSuppressesLateCallback",
        "warmUpActivatesOnceBeforeFirstKeyWithoutPublishingState",
        "candidatePagingAndSelectionUseSameOrderedWorkerAndExactIdentity",
    ):
        if token not in controller_test:
            violations.append(Violation("RIM004_CONTROLLER_TEST", token))

    config_compact = _compact(config)
    for token in (
        'OPTION_SIMPLIFICATION="simplification"',
        'OPTION_ASCII_PUNCTUATION="ascii_punct"',
        'OPTION_FULL_SHAPE="full_shape"',
        "List.of(OPTION_SIMPLIFICATION,OPTION_ASCII_PUNCTUATION,OPTION_FULL_SHAPE)",
        "schemas.contains(requestedSchema)?requestedSchema:schemas.get(0)",
        "booleansimplifiedOutput,booleanasciiPunctuation,booleanfullShape",
        "caseOPTION_SIMPLIFICATION->simplifiedOutput;",
        "caseOPTION_ASCII_PUNCTUATION->asciiPunctuation;",
        "caseOPTION_FULL_SHAPE->fullShape;",
        "default->thrownewIllegalArgumentException(\"unsupportedRimeoption\")",
    ):
        if token not in config_compact:
            violations.append(Violation("RIM006_CONFIG_CONTRACT", token))
    if any(token in config for token in (
        "Map<", "JSONObject", "Bundle", "Intent", "java.net", "InputConnection",
    )):
        violations.append(Violation(
            "RIM006_CONFIG_CONTRACT", "configuration vocabulary must remain closed"
        ))
    for token in (
        'FILE_NAME="opentypeless_rime_runtime_config_v1"',
        "getSharedPreferences(FILE_NAME,Context.MODE_PRIVATE)",
        "RimeRuntimeConfig.resolved(",
        "if(!schemas.contains(config.schemaId()))",
        ".commit()",
    ):
        if token not in _compact(preferences):
            violations.append(Violation("RIM006_PERSISTENCE_CONTRACT", token))
    for token in (
        "runtimePreferences.save(configuration,schemas)",
        "schemaChoices.removeAllViews()",
        "configuration.fullShape()",
        "configuration.asciiPunctuation()",
        "configuration.simplifiedOutput()",
    ):
        if token not in _compact(activity):
            violations.append(Violation("RIM006_SETTINGS_WIRING", token))
    for token in (
        "missingSchemaFallsBackToFirstInstalledSchema",
        "fullShapeDisablesAsciiPunctuationDeterministically",
        "schemaAndOptionVocabularyAreClosed",
    ):
        if token not in config_test:
            violations.append(Violation("RIM006_CONFIG_TEST", token))
    for token in (
        "rimeRuntimePreferences.load(runtime.selectedSchemas())",
        "availableRimeConfig=runtime==null?null:config",
        "runtime.selectedSchemas().contains(config.schemaId())",
    ):
        if token not in service_compact:
            violations.append(Violation("RIM006_RUNTIME_WIRING", token))
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
    print("RIM-001/RIM-004/RIM-005/RIM-006 Rime engine and configuration boundary passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

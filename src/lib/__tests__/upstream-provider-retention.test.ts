import { describe, expect, it } from 'vitest'
import ts from 'typescript'
import { LLM_PROVIDERS, STT_PROVIDERS } from '../constants'
import tauriSource from '../tauri.ts?raw'

const upstreamDesktopFunctions = [
  'startRecording',
  'stopRecording',
  'abortRecording',
  'setActiveTranslationTarget',
  'getLatestMappingCandidate',
  'listCustomAppMappings',
  'saveCustomAppMapping',
  'updateCustomAppMapping',
  'setCustomAppMappingEnabled',
  'deleteCustomAppMapping',
  'resetCustomAppMappings',
  'setFamilySceneAssignment',
  'requestBrowserAccess',
  'getConfig',
  'updateConfig',
  'configWithoutProviderSecrets',
  'saveOnboardingConfig',
  'getSttRecordingCapability',
  'getLlmModelCapability',
  'getCredentialStatus',
  'readCredential',
  'setCredential',
  'clearCredential',
  'migrateLegacyCredentials',
  'setCapsuleAutoHide',
  'getPlatformCapabilities',
  'getHotkeyRegistrationError',
  'getHotkeyStatus',
  'getSystemDiagnostics',
  'getSttProviderDiagnostics',
  'testSttConnection',
  'testLlmConnection',
  'benchSttConnection',
  'benchLlmConnection',
  'fetchLlmModels',
  'updateHotkey',
  'updateAskHotkey',
  'pauseHotkey',
  'resumeHotkey',
  'askAnything',
  'showAskWindow',
  'startAskFlow',
  'startAskDictation',
  'stopAskDictation',
  'stopAskFlow',
  'abortAskDictation',
  'takePendingAskMessage',
  'getHistory',
  'clearHistory',
  'restoreBackupData',
  'getDictionary',
  'addDictionaryEntry',
  'removeDictionaryEntry',
  'updateDictionaryEntry',
  'getCorrectionRules',
  'addCorrectionRule',
  'removeCorrectionRule',
  'setCorrectionRuleEnabled',
  'updateCorrectionRule',
  'previewDictionaryImport',
  'commitDictionaryImport',
  'exportDictionaryJson',
  'exportDictionaryCsv',
  'setAutoStart',
  'checkAccessibilityPermission',
  'requestAccessibilityPermission',
  'waitForAccessibilityPermission',
  'loadOnboardingCompleted',
  'saveOnboardingCompleted',
]

const upstreamSttProviders = [
  'deepgram',
  'assemblyai',
  'aliyun-qwen3-asr',
  'volcengine-doubao',
  'glm-asr',
  'openai-whisper',
  'groq-whisper',
  'siliconflow',
  'apple-speech',
  'custom-whisper',
  'cloud',
]

const upstreamLlmProviders = [
  'zhipu',
  'deepseek',
  'siliconflow',
  'openai',
  'gemini',
  'moonshot',
  'doubao',
  'qwen',
  'groq',
  'claude',
  'ollama',
  'openrouter',
  'cloud',
]

function exportedFunctionNames(source: string): string[] {
  const syntax = ts.createSourceFile('tauri.ts', source, ts.ScriptTarget.Latest, true)
  return syntax.statements.flatMap((statement) =>
    ts.isFunctionDeclaration(statement) &&
    statement.name &&
    statement.modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.ExportKeyword)
      ? [statement.name.text]
      : [],
  )
}

describe('provider retention from upstream f5ad60298f535e0fff716a749802138ee409dea7', () => {
  it('ignores commented exports and recognizes real declarations regardless of whitespace', () => {
    expect(
      exportedFunctionNames(
        '/*\nexport function commented() {}\n*/\n  export async function actual() {}\nfunction internal() {}\n',
      ),
    ).toEqual(['actual'])
  })

  it('retains the upstream desktop function declarations', () => {
    const current = new Set(exportedFunctionNames(tauriSource))
    expect(upstreamDesktopFunctions).toHaveLength(69)
    expect(new Set(upstreamDesktopFunctions).size).toBe(69)
    expect(upstreamDesktopFunctions.filter((name) => !current.has(name))).toEqual([])
  })

  it.each([
    { kind: 'STT', baseline: upstreamSttProviders, current: STT_PROVIDERS },
    { kind: 'LLM', baseline: upstreamLlmProviders, current: LLM_PROVIDERS },
  ])(
    'retains every upstream $kind choice without duplicate identifiers',
    ({ baseline, current }) => {
      const values = current.map((provider) => provider.value)
      expect(baseline.filter((provider) => !values.includes(provider))).toEqual([])
      expect(new Set(values).size).toBe(values.length)
    },
  )
})

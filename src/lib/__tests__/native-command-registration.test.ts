import { describe, expect, it } from 'vitest'
import ts from 'typescript'
import backend from '../../../src-tauri/src/lib.rs?raw'
import configCommands from '../../../src-tauri/src/commands/config.rs?raw'
import sttCommands from '../../../src-tauri/src/commands/stt.rs?raw'
import configStorage from '../../../src-tauri/src/storage/mod.rs?raw'
import translationCommands from '../../../src-tauri/src/commands/translation.rs?raw'
import polishProvider from '../../../src-tauri/src/llm/openai.rs?raw'
import askCommands from '../../../src-tauri/src/commands/ask.rs?raw'
import pipelineSource from '../../../src-tauri/src/pipeline.rs?raw'

const frontendSources = import.meta.glob<string>(
  ['../../**/*.ts', '../../**/*.tsx', '!../../**/__tests__/**', '!../../test-setup.ts'],
  { query: '?raw', import: 'default', eager: true },
)
function productionRegistration(source: string): string | undefined {
  const entrypoint = source.indexOf('pub fn run() {')
  if (entrypoint < 0) return undefined
  return source
    .slice(entrypoint)
    .match(/\.invoke_handler\(tauri::generate_handler!\[([\s\S]*?)\]\)/)?.[1]
}

const registration = productionRegistration(backend)
it('exposes only generation-owned consent revocation in the production handler', () => {
  expect(registration).toContain('history_sync_commands::revoke_history_sync_consent_generation')
  expect(registration).not.toMatch(/history_sync_commands::revoke_history_sync_consent\s*,/)
})

function literalInvokeCommands(source: string, filename: string): string[] {
  const syntax = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true)
  const host = ts.createCompilerHost({ noLib: true })
  host.getSourceFile = (requested) => (requested === filename ? syntax : undefined)
  host.fileExists = (requested) => requested === filename
  host.readFile = (requested) => (requested === filename ? source : undefined)
  const checker = ts.createProgram([filename], { noLib: true }, host).getTypeChecker()
  const importedBindings = new Set<ts.Symbol>()
  for (const statement of syntax.statements) {
    if (
      !ts.isImportDeclaration(statement) ||
      !ts.isStringLiteral(statement.moduleSpecifier) ||
      statement.moduleSpecifier.text !== '@tauri-apps/api/core' ||
      statement.importClause?.isTypeOnly
    ) {
      continue
    }
    const bindings = statement.importClause?.namedBindings
    if (!bindings || !ts.isNamedImports(bindings)) continue
    for (const binding of bindings.elements) {
      if (binding.isTypeOnly || (binding.propertyName ?? binding.name).text !== 'invoke') continue
      const symbol = checker.getSymbolAtLocation(binding.name)
      if (symbol) importedBindings.add(symbol)
    }
  }
  const commands: string[] = []
  const visit = (node: ts.Node) => {
    if (
      ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) &&
      importedBindings.has(checker.getSymbolAtLocation(node.expression)!)
    ) {
      const argument = node.arguments[0]
      if (
        argument &&
        (ts.isStringLiteral(argument) || ts.isNoSubstitutionTemplateLiteral(argument))
      ) {
        commands.push(argument.text)
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(syntax)
  return commands
}

const commandCalls = Object.entries(frontendSources).flatMap(([path, source]) =>
  literalInvokeCommands(source, path).map((command) => ({
    path,
    command,
  })),
)
const commands = commandCalls.map((call) => call.command)
const registered = new Set(
  (registration ?? '')
    .split(',')
    .map((entry) => entry.trim().split('::').pop())
    .filter(Boolean),
)

describe('desktop command wiring', () => {
  it('guards managed STT capability mutations before reading or saving configuration', () => {
    for (const name of ['cache_managed_stt_capability', 'clear_managed_stt_capability']) {
      const command = sttCommands.match(
        new RegExp(`pub async fn ${name}(?:<[^>]+>)?\\([\\s\\S]*?\\n}\\n`),
      )?.[0]
      expect(command).toBeDefined()
      const guard = command!.indexOf('ensure_stt_configuration_window(window.label())?;')
      expect(guard).toBeGreaterThanOrEqual(0)
      expect(guard).toBeLessThan(command!.indexOf('state.load().await'))
      expect(registered.has(name)).toBe(true)
      expect(command).toContain('.try_update_current_config_commit(')
      expect(command).not.toContain('.save(&config)')
      if (name === 'clear_managed_stt_capability') {
        const beforeUpdate = command!.split('.try_update_current_config_commit(')[0]
        expect(beforeUpdate).not.toContain('managed_stt_capability_state.is_none()')
      }
    }
  })

  it('keeps the Ask transcript worker when recording publication is superseded by stop', () => {
    const publication = askCommands
      .split('state.publish_recording(recording_session_id, || {')[1]
      ?.split('let state_inner = state.0.clone();')[0]
    expect(publication).toBeDefined()
    expect(publication).not.toContain('provider.disconnect().await')
    expect(publication).not.toContain('return Err')
  })

  it('points missing speech credentials to the current Models page', () => {
    for (const source of [askCommands, pipelineSource]) {
      expect(source).toContain('Please set it in Models -> Speech Recognition.')
      expect(source).not.toContain('Please set it in Settings -> Speech Recognition.')
    }
  })

  it('rechecks Ask startup ownership after configuration loading and before acquisition', () => {
    const startup = askCommands.split('let result = async {')[1]?.split('let capture_ready_at')[0]
    expect(startup).toBeDefined()
    const loaded = startup!.indexOf('config_state.load().await')
    const afterLoad = startup!.indexOf('if !state.owns_startup(generation)', loaded)
    expect(afterLoad).toBeGreaterThan(loaded)
    expect(afterLoad).toBeLessThan(startup!.indexOf('snapshot_for_recording_enabled'))
    const beforeAcquisition = startup!.indexOf('if !state.owns_startup(generation)', afterLoad + 1)
    expect(beforeAcquisition).toBeGreaterThan(afterLoad)
    expect(beforeAcquisition).toBeLessThan(startup!.indexOf('warm_managed_cloud_on_intent'))
    expect(beforeAcquisition).toBeLessThan(startup!.indexOf('AudioCaptureHandle::start'))
  })

  it('keeps cancelled CLI Ask silent without logging raw failures', () => {
    const handler = backend.split('CliAction::Ask => {')[1]?.split('\npub fn run()')[0]
    expect(handler).toBeDefined()
    expect(handler).toContain('if error != commands::ask::ASK_CANCELLED')
    expect(handler).toContain('tracing::error!("CLI ask failed");')
    expect(handler).not.toContain('CLI ask failed: {error}')
  })

  it('does not report discarded Ask startup resources as a successful recording', () => {
    const discarded = askCommands.match(
      /if should_discard_started_resources \{[\s\S]*?\n {8}\}/,
    )?.[0]
    expect(discarded).toBeDefined()
    expect(discarded).toContain('handle.stop()')
    expect(discarded).toContain('provider.disconnect().await')
    expect(discarded).toContain('return Err(ASK_CANCELLED.to_string());')
    expect(discarded).not.toContain('return Ok(start_result)')
    const flow = askCommands.match(/pub async fn start_ask_flow\([\s\S]*?\n}\n/)?.[0]
    expect(flow).toBeDefined()
    expect(flow).toContain('if message != ASK_CANCELLED')
  })

  it('rejects a busy local Ask start instead of reporting a successful empty session', () => {
    const command = askCommands.match(/pub async fn start_ask_dictation\([\s\S]*?\n}\n/)?.[0]
    expect(command).toBeDefined()
    expect(command).toContain('return Err("Ask is already busy".to_string());')
    expect(command).not.toContain('return Ok(AskDictationStartResult::empty())')
  })

  it('checks the caller window before reopening a saved Ask answer', () => {
    const command = askCommands.match(
      /pub async fn show_saved_ask_answer(?:<[^>]+>)?\([\s\S]*?\n}\n/,
    )?.[0]
    expect(command).toBeDefined()
    expect(command).toContain('window: tauri::Window')
    const guard = command!.indexOf('ensure_saved_answer_window(window.label())?;')
    expect(guard).toBeGreaterThanOrEqual(0)
    const readSources = command!.indexOf('.read_saved_answer_sync(')
    const publish = command!.indexOf('state.claim_completed_publication(')
    expect(readSources).toBeGreaterThan(guard)
    expect(command!.indexOf('spawn_blocking')).toBeGreaterThan(guard)
    expect(readSources).toBeGreaterThan(command!.indexOf('spawn_blocking'))
    expect(publish).toBeGreaterThan(readSources)
    expect(command!.indexOf('state.reserve_saved_publication()')).toBeGreaterThan(guard)
    const initialContentCheck = command!.indexOf('ensure_saved_answer_content(&window)?;')
    const finalContentCheck = command!.lastIndexOf('ensure_saved_answer_content(&window)?;')
    expect(initialContentCheck).toBeGreaterThan(guard)
    expect(initialContentCheck).toBeLessThan(readSources)
    expect(finalContentCheck).toBeGreaterThan(readSources)
    expect(finalContentCheck).toBeLessThan(publish)
    expect(registered.has('show_saved_ask_answer')).toBe(true)
  })

  it('excludes comments and example strings while collecting literal invoke expressions', () => {
    expect(
      literalInvokeCommands(
        [
          "import { invoke } from '@tauri-apps/api/core';",
          "// invoke('commented_command')",
          'const example = "invoke(\'example_command\')";',
          "invoke<{ result: string }>('get_config');",
          'invoke(`start_recording`);',
          'invoke(dynamicCommand);',
          "service.invoke('unrelated_command');",
        ].join('\n'),
        'example.ts',
      ),
    ).toEqual(['get_config', 'start_recording'])
  })

  it('follows the Tauri import alias and excludes shadowed or unrelated invoke bindings', () => {
    expect(
      literalInvokeCommands(
        [
          "import { invoke as desktopInvoke } from '@tauri-apps/api/core';",
          "import { invoke } from 'unrelated-service';",
          "desktopInvoke('get_config');",
          "invoke('unrelated');",
          "function local(desktopInvoke: (command: string) => void) { desktopInvoke('shadowed'); }",
        ].join('\n'),
        'example.ts',
      ),
    ).toEqual(['get_config'])
  })

  it('rejects empty polish responses without exposing response payloads', () => {
    const diagnostic = polishProvider.match(
      /fn ensure_polish_answer\(text: &str\)[\s\S]*?(?=\nfn check_polish_stream_size)/,
    )?.[0]
    expect(diagnostic).toBeDefined()
    expect(diagnostic).toContain('text.trim().is_empty()')
    expect(diagnostic).toContain('LLM returned an empty answer')
    expect(diagnostic).toContain('return Err(AppError::Config')
    expect(diagnostic).not.toContain('full response')
    expect(diagnostic).not.toContain('format!')
    expect(polishProvider).toContain('ensure_polish_answer(&text)?;')
  })

  it('does not rewrite a stale full config snapshot after translation persistence fails', () => {
    const production = translationCommands.split('#[cfg(test)]')[0]
    expect(production).toContain('.try_update_current_config_commit(')
    expect(production).not.toContain('.save(&config)')
    expect(production).toContain('let _ = switch_target(previous_operation_target)')
    expect(production).not.toContain('save(&previous_config)')
    expect(production).toContain('save_translation_target(')
  })

  it('commits ordinary configuration cache only after persistence succeeds', () => {
    const save = configStorage.match(
      /pub async fn save\(&self, config: &AppConfig\)[\s\S]*?\n {4}}\n/,
    )?.[0]
    expect(save).toBeDefined()
    const persistence = save!.indexOf('self.persist_config(&config)?')
    const cacheCommit = save!.indexOf('Some(config')
    expect(persistence).toBeGreaterThanOrEqual(0)
    expect(cacheCommit).toBeGreaterThan(persistence)
  })

  it('filters endpoint URLs from STT request failures before returning diagnostics', () => {
    const failures = [...sttCommands.matchAll(/\.send\(\)\s*\.await\s*\.map_err\(([^;]+?)\)\?/g)]
    expect(failures.length).toBeGreaterThan(0)
    for (const failure of failures) {
      expect(failure[1]).toBe('crate::error::request_error_message')
    }
  })

  it('routes ordinary configuration reads through the renderer secret filter', () => {
    const command = configCommands.match(/pub async fn get_config\([\s\S]*?\n}\n/)?.[0]
    expect(command).toBeDefined()
    expect(command).toContain('.map(config_for_renderer)')
    expect(registered.has('get_config')).toBe(true)
  })

  it('finds the real frontend commands and native registration block', () => {
    expect(registration).toBeDefined()
    expect(commands.length).toBeGreaterThan(60)
    expect(commands).toContain('start_recording')
    expect(commands).toContain('restore_backup_data')
    expect(commandCalls).toContainEqual({
      path: '../cloud-session.ts',
      command: 'get_session_token',
    })
    expect(commandCalls).toContainEqual({
      path: '../managed-stt-capability.ts',
      command: 'cache_managed_stt_capability',
    })
    expect(registered.has('start_recording')).toBe(true)
  })

  it('ignores test-only registration blocks before the production entrypoint', () => {
    expect(
      productionRegistration(
        'fn test() { .invoke_handler(tauri::generate_handler![test_only]) }\n' +
          'pub fn run() { .invoke_handler(tauri::generate_handler![start_recording, stop_recording]) }',
      ),
    ).toBe('start_recording, stop_recording')
    expect(productionRegistration('fn test() { test_only }')).toBeUndefined()
  })

  it('registers literal application commands across frontend modules', () => {
    expect(
      commandCalls.filter(
        ({ command }) => !command.startsWith('plugin:') && !registered.has(command),
      ),
    ).toEqual([])
  })
})

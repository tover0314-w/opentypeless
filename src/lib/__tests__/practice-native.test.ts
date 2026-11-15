import { describe, expect, it } from 'vitest'
import source from '../../../src-tauri/src/practice.rs?raw'
import commands from '../../../src-tauri/src/commands/practice.rs?raw'
import entry from '../../../src-tauri/src/lib.rs?raw'
import pipeline from '../../../src-tauri/src/pipeline.rs?raw'

describe('isolated native practice wiring', () => {
  it('creates task cleanup before scheduling so an unpolled task can release ownership', () => {
    const start = source.slice(source.indexOf('pub fn start('))
    expect(start.indexOf('task_cleanup(guard')).toBeGreaterThan(0)
    expect(start.indexOf('task_cleanup(guard')).toBeLessThan(
      start.indexOf('tauri::async_runtime::spawn'),
    )
  })
  it('validates polish configuration before microphone startup', () => {
    const run = source.slice(source.indexOf('async fn run('))
    expect(run.indexOf('validate_polish_configuration(&config)?')).toBeGreaterThan(0)
    expect(run.indexOf('validate_polish_configuration(&config)?')).toBeLessThan(
      run.indexOf('AudioCaptureHandle::start'),
    )
  })
  it('routes model chunks into practice state rather than ordinary output', () => {
    expect(source).toContain('provider.polish(config, request, Some(on_chunk))')
    expect(source).toContain('append_polish_preview(&callback_session, chunk)')
  })
  it('cancels practice on app exit independently of window close events', () => {
    const exit = entry.slice(entry.indexOf('.run(|_app, _event|'))
    expect(exit).toContain('practice::cancel_on_exit(_app);')
    expect(exit.indexOf('practice::cancel_on_exit')).toBeLessThan(
      exit.indexOf('audio::output_mute::restore_on_exit'),
    )
  })
  it('checks practice ownership before ordinary recording stop waits for the lock', () => {
    const stop = pipeline.slice(pipeline.indexOf('async fn stop_recording('))
    expect(stop).toContain('try_state::<crate::practice::PracticeState>()')
    expect(stop).toContain('acquire_voice_stop_guard(&self.pipeline_lock, practice_busy)')
  })
  it('cancels practice natively before the main window can hide or close', () => {
    const handler = entry.slice(entry.indexOf('main_window.on_window_event'))
    const closing = handler.slice(0, handler.indexOf('// Start minimized'))
    expect(closing).toContain('practice::cancel_for_window_event(&handle, "main", event);')
    expect(closing.indexOf('practice::cancel_for_window_event')).toBeLessThan(
      closing.indexOf('w.hide()'),
    )
    expect(source).toContain('tauri::WindowEvent::Destroyed')
    expect(source).toContain('tauri::WindowEvent::CloseRequested')
    expect(source).toContain('state.cancel_current()')
  })
  it('registers main-only session commands without external output or persistence paths', () => {
    for (const command of ['start_practice', 'stop_practice', 'cancel_practice', 'get_practice']) {
      expect(entry).toContain(`commands::practice::${command}`)
      expect(commands).toContain(command)
    }
    expect(commands).toContain('ensure_main(window.label())?')
    expect(source).toContain('try_voice_start_guard')
    for (const forbidden of [
      'output_text',
      'clipboard',
      'capture_selected_text',
      'snapshot_for_recording',
      'HistoryStore',
      'save_recording',
      'edit_learning::track_after_insert',
      'DictionaryStore',
      'add_auto',
      'last_transcript',
    ]) {
      expect(source).not.toContain(forbidden)
    }
    expect(source.match(/crate::edit_learning::[A-Za-z_]+/g)).toEqual([
      'crate::edit_learning::cancel',
    ])
  })
})

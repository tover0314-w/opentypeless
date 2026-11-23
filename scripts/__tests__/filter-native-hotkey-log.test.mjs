import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const script = fileURLToPath(new URL('../filter-native-hotkey-log.mjs', import.meta.url))

test('retains only allowlisted roles for dispatch and registration rejection', () => {
  const events = filtered(
    'DEBUG Dispatching configured shortcut role="ask" secret=private-value\n' +
      'DEBUG Shortcut blocked by registration state role="dictation"\n' +
      'DEBUG Native shortcut dispatch paused role="translate_selection"\n' +
      'DEBUG Dispatching configured shortcut role="private-value"\n' +
      'DEBUG Dispatching configured shortcut role="ask" role="dictation"\n',
  )
  assert.deepEqual(events.map(({ observedAt, ...event }) => event), [
    { event: 'shortcut_dispatch', role: 'ask' },
    { event: 'shortcut_registration_blocked', role: 'dictation' },
    { event: 'shortcut_paused', role: 'translate_selection' },
  ])
})

function filtered(input) {
  const result = spawnSync(process.execPath, [script], { input, encoding: 'utf8' })
  assert.equal(result.status, 0)
  assert.equal(result.stderr, '')
  return result.stdout.trim().split('\n').filter(Boolean).map((line) => JSON.parse(line))
}

test('retains only fixed messages and validated native state fields', () => {
  const result = filtered(
    '2026-11-15T10:00:00Z DEBUG opentypeless_lib::native_hotkey: Native Fn dispatch state pressed=true was_pressed=false awaiting_release=true held_combo_count=1 pending_base_press=false combo_used=false secret=synthetic-secret\n' +
      'DEBUG Native Fn dispatch outcome pressed=true business_accepted=false transcript=synthetic-private-text\n' +
      'DEBUG Native release barrier reconciled\n',
  )
  assert.deepEqual(result.map(({ observedAt, ...event }) => {
    assert.ok(Number.isFinite(Date.parse(observedAt)))
    return event
  }), [
    {
      event: 'native_fn_state',
      pressed: true,
      was_pressed: false,
      awaiting_release: true,
      held_combo_count: 1,
      pending_base_press: false,
      combo_used: false,
    },
    { event: 'native_fn_outcome', pressed: true, business_accepted: false },
    { event: 'native_release_reconciled' },
  ])
})

test('drops unrelated, malformed, duplicate and oversized lines without echoing them', () => {
  assert.deepEqual(filtered(
    'DEBUG Transcript: synthetic-private-text\n' +
      'DEBUG Observed native Fn edge pressed=synthetic-secret\n' +
      'DEBUG Observed native Fn edge pressed=true pressed=false\n' +
      'DEBUG Native Fn dispatch state pressed=true held_combo_count=999999\n' +
      `DEBUG Observed native Fn edge pressed=true ${'private'.repeat(2000)}\n`,
  ), [])
})

test('strips terminal formatting and accepts a final line without a newline', () => {
  const result = filtered('\u001b[32mDEBUG\u001b[0m Observed native Fn edge \u001b[3mpressed\u001b[0m=true')
  assert.equal(result.length, 1)
  assert.equal(result[0].event, 'native_fn_edge')
  assert.equal(result[0].pressed, true)
})

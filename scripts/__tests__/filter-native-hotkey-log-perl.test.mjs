import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const script = fileURLToPath(new URL('../filter-native-hotkey-log.pl', import.meta.url))

test('emits a complete short line before the input pipe closes', async () => {
  const child = spawn('/usr/bin/perl', [script], { stdio: ['pipe', 'pipe', 'pipe'] })
  try {
    const output = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Live log line was buffered')), 1500)
      child.stdout.once('data', (chunk) => {
        clearTimeout(timer)
        resolve(chunk.toString())
      })
      child.once('error', (error) => {
        clearTimeout(timer)
        reject(error)
      })
      child.stdin.write('DEBUG Observed native Fn edge pressed=true\n')
    })
    assert.equal(JSON.parse(output).event, 'native_fn_edge')
    assert.equal(child.stdin.writable, true)
  } finally {
    child.stdin.end()
    child.kill()
  }
})

function filtered(input) {
  const result = spawnSync('/usr/bin/perl', [script], { input, encoding: 'utf8' })
  assert.equal(result.status, 0)
  assert.equal(result.stderr, '')
  return result.stdout.trim().split('\n').filter(Boolean).map(JSON.parse)
}

test('preserves validated state and roles without returning raw log contents', () => {
  const events = filtered(
    'DEBUG Native Fn dispatch state pressed=true was_pressed=false awaiting_release=true held_combo_count=1 pending_base_press=false combo_used=false secret=private\n' +
      'DEBUG Dispatching configured shortcut role="ask" transcript=private\n' +
      'DEBUG Native Fn dispatch outcome pressed=false business_accepted=false\n',
  )
  assert.deepEqual(events.map(({ observedAt, ...event }) => {
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
    { event: 'shortcut_dispatch', role: 'ask' },
    { event: 'native_fn_outcome', pressed: false, business_accepted: false },
  ])
})

test('drops invalid fields and oversized records then recovers on the next line', () => {
  const events = filtered(
    'DEBUG Observed native Fn edge pressed=true pressed=false\n' +
      'DEBUG Dispatching configured shortcut role="private"\n' +
      `DEBUG Observed native Fn edge pressed=true ${'private'.repeat(2000)}\n` +
      'DEBUG Observed native Fn edge pressed=private\n' +
      'DEBUG Native Fn dispatch state pressed=true held_combo_count=9999\n' +
      '\u001b[32mDEBUG\u001b[0m Observed native Fn edge \u001b[3mpressed\u001b[0m=false',
  )
  assert.equal(events.length, 1)
  assert.equal(events[0].event, 'native_fn_edge')
  assert.equal(events[0].pressed, false)
})

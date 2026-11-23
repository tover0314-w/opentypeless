const maximumLineLength = 4096
const definitions = [
  ['Observed native Fn edge', 'native_fn_edge', ['pressed']],
  [
    'Native Fn dispatch state',
    'native_fn_state',
    ['pressed', 'was_pressed', 'awaiting_release', 'held_combo_count', 'pending_base_press', 'combo_used'],
  ],
  ['Native Fn dispatch outcome', 'native_fn_outcome', ['pressed', 'business_accepted']],
  ['Native release barrier reconciled', 'native_release_reconciled', []],
  ['Dispatching configured shortcut', 'shortcut_dispatch', ['role']],
  ['Shortcut blocked by registration state', 'shortcut_registration_blocked', ['role']],
  ['Native shortcut dispatch paused', 'shortcut_paused', ['role']],
]

function emitLine(raw) {
  const line = raw.replace(/\x1b\[[0-9;]*m/g, '')
  const matches = definitions.filter(([message]) => line.includes(message))
  if (matches.length !== 1) return
  const [, event, fields] = matches[0]
  const output = { observedAt: new Date().toISOString(), event }
  for (const field of fields) {
    const values = [...line.matchAll(new RegExp(`\\b${field}=([^\\s]+)`, 'g'))]
    if (values.length !== 1) return
    const value = values[0][1]
    if (field === 'role') {
      const role = value.replace(/^"(.*)"$/, '$1')
      if (!['dictation', 'ask', 'translate_selection'].includes(role)) return
      output[field] = role
    } else if (field === 'held_combo_count') {
      if (!/^\d{1,3}$/.test(value) || Number(value) > 128) return
      output[field] = Number(value)
    } else {
      if (value !== 'true' && value !== 'false') return
      output[field] = value === 'true'
    }
  }
  process.stdout.write(`${JSON.stringify(output)}\n`)
}

let buffer = ''
let dropping = false
process.stdin.setEncoding('utf8')
process.stdin.on('data', (chunk) => {
  for (const character of chunk) {
    if (character === '\n') {
      if (!dropping) emitLine(buffer)
      buffer = ''
      dropping = false
    } else if (!dropping) {
      buffer += character
      if (buffer.length > maximumLineLength) {
        buffer = ''
        dropping = true
      }
    }
  }
})
process.stdin.on('end', () => {
  if (!dropping && buffer) emitLine(buffer)
})

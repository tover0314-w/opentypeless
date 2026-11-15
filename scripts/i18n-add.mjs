#!/usr/bin/env node
// Merges new UI copy into every locale file. Usage: node scripts/i18n-add.mjs <patch.json>
// Patch shape: { "<locale>": { "<dotted.key>": "text" } }. Every key must be present for all locales.
import fs from 'node:fs'
import path from 'node:path'

const LOCALES = ['en', 'zh', 'ja', 'ko', 'fr', 'de', 'es', 'pt', 'ru', 'it']
const dir = path.resolve('src/i18n/locales')
const patch = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))

const keys = Object.keys(patch.en ?? {})
for (const locale of LOCALES) {
  const missing = keys.filter((key) => !(patch[locale] && key in patch[locale]))
  if (missing.length) {
    console.error(`${locale} is missing ${missing.length} keys: ${missing.join(', ')}`)
    process.exit(1)
  }
}

function setPath(target, dotted, value) {
  const parts = dotted.split('.')
  let node = target
  for (const part of parts.slice(0, -1)) {
    if (typeof node[part] !== 'object' || node[part] === null) node[part] = {}
    node = node[part]
  }
  node[parts.at(-1)] = value
}

for (const locale of LOCALES) {
  const file = path.join(dir, `${locale}.json`)
  const messages = JSON.parse(fs.readFileSync(file, 'utf8'))
  for (const key of keys) setPath(messages, key, patch[locale][key])
  fs.writeFileSync(file, `${JSON.stringify(messages, null, 2)}\n`)
}
console.log(`Merged ${keys.length} keys into ${LOCALES.length} locales.`)

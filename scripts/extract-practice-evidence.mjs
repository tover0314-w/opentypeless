import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { parse } from '@babel/parser'

const archive = '/Applications/Typeless.app/Contents/Resources/app.asar'
const nativeSourceLink = process.argv[3] === 'native-source-link'
const markdownOffsets = process.argv[3] === 'markdown-offsets'
const activeTranslation = process.argv[3] === 'active-translation'
const translationTiming = process.argv[3] === 'translation-timing'
const askCitationRenderer = process.argv[3] === 'ask-citation-renderer'
const askCitationLinks = process.argv[3] === 'ask-citation-links'
const askCitations = process.argv[3] === 'ask-citations' || askCitationRenderer || askCitationLinks
const member = nativeSourceLink
  ? 'dist/main/index.js'
  : markdownOffsets
    ? 'dist/renderer/static/js/Bm9GcyXO.js'
    : askCitations
      ? 'dist/renderer/static/js/BKM3Eizp.js'
      : translationTiming
        ? 'dist/renderer/static/js/B3THag5_.mjs'
        : activeTranslation
          ? 'dist/renderer/static/js/6JaRAq4A.js'
          : 'dist/renderer/static/js/Dx8DUWFx.js'
const output = process.argv[2]
const translationOnly = process.argv[3] === 'translation'
const translationControls = process.argv[3] === 'translation-controls'
const translationDialog = process.argv[3] === 'translation-dialog'
const translationPreferred = process.argv[3] === 'translation-preferred'
if (!output || fs.existsSync(output)) throw new Error('An absent output directory is required')
const descriptor = fs.openSync(archive, 'r')
const prefix = Buffer.alloc(16)
fs.readSync(descriptor, prefix, 0, prefix.length, 0)
const header = Buffer.alloc(prefix.readUInt32LE(12))
fs.readSync(descriptor, header, 0, header.length, 16)
let entry = JSON.parse(header.toString())
for (const segment of member.split('/')) entry = entry.files[segment]
const bytes = Buffer.alloc(entry.size)
fs.readSync(descriptor, bytes, 0, bytes.length, 8 + prefix.readUInt32LE(4) + Number(entry.offset))
fs.closeSync(descriptor)
const digest = crypto.createHash('sha256').update(bytes).digest('hex')
const expectedDigest = nativeSourceLink
  ? '4252373e8e42e77f12a2a48cc7a14d7ab8ede6bbb834c13aa7da915d4699275f'
  : markdownOffsets
    ? '8749eed03d2615c80beaedd7536d1af0afdd84a4c577504cc0f5aa99cafba365'
    : askCitations
      ? 'ff2f4c1bbd2c33166bab856b3dc8c4dc30fc89f3f472a7740f38c680a9ca5aac'
      : translationTiming
        ? 'b9756ffaf1ce5fa9d7e5093b396075389572a29eec1d13381722c0b99e9f9563'
        : activeTranslation
          ? '631db34a930a6a70934e0911f645043a2fa70c13f24709ed1ad95d3414f84612'
          : '82f877388db8af27aa8c71541e21d4d3612a333443c7195165f9cf3ecde58288'
if (digest !== expectedDigest) {
  throw new Error('Reference member identity changed')
}
const source = bytes.toString()
const ast = parse(source, { sourceType: 'module' })
const selected = new Map()
function walk(node, ancestors = []) {
  if (!node || typeof node !== 'object') return
  if (
    nativeSourceLink &&
    node.type === 'ArrowFunctionExpression' &&
    node.params.some((parameter) => parameter.name === '_0x720f78')
  ) {
    selected.set(node.start, { node, seeds: ['native-page-open-url-handler'] })
  }
  if (
    markdownOffsets &&
    node.type === 'FunctionDeclaration' &&
    ['_0x596bd9', '_0x495ab4', '_0x875063'].includes(node.id?.name)
  ) {
    selected.set(node.start, { node, seeds: ['markdown-offset-consumption'] })
  }
  if (
    askCitationLinks &&
    node.type === 'VariableDeclarator' &&
    ['oe', 'ie', '$'].includes(node.id?.name)
  ) {
    selected.set(node.start, { node, seeds: ['citation-link-and-expansion'] })
  }
  if (
    askCitationRenderer &&
    node.type === 'VariableDeclarator' &&
    ['_0x3ea1aa', '_0x2575a5'].includes(node.id?.name)
  ) {
    selected.set(node.start, { node, seeds: ['markdown-citation-renderer'] })
  }
  if (translationTiming && node.type === 'CallExpression' && node.callee?.name === '_0x3bc2b0') {
    const owner = [...ancestors]
      .reverse()
      .find((parent) => ['ArrowFunctionExpression', 'FunctionExpression'].includes(parent.type))
    if (owner) selected.set(owner.start, { node: owner, seeds: ['translation-target-read'] })
  }
  if (
    !translationTiming &&
    !nativeSourceLink &&
    !markdownOffsets &&
    !askCitations &&
    !activeTranslation &&
    node.type === 'VariableDeclarator' &&
    (translationPreferred
      ? ['Po']
      : translationDialog
        ? ['Kt', 'No']
        : translationControls
          ? ['Ho', 'pn', 'na']
          : translationOnly
            ? ['be']
            : ['be', 'jn', 'qi', 'Ji']
    ).includes(node.id?.name)
  ) {
    selected.set(node.start, { node, seeds: [`supporting-declaration:${node.id.name}`] })
  }
  if (
    !translationControls &&
    !translationDialog &&
    !translationPreferred &&
    !translationTiming &&
    !nativeSourceLink &&
    !markdownOffsets &&
    !askCitationRenderer &&
    !askCitationLinks &&
    node.type === 'StringLiteral' &&
    (askCitations
      ? /grounding_chunk_indices/
      : activeTranslation
        ? /translationModeAlertPreferredLanguageCode/
        : translationOnly
          ? /onboarding__translation__settings|onboarding__translation__example/
          : /typeless-notes-onboarding-input|keyboard-input:|transcription:|onboarding__dictation__example|onboarding__ask_anything__help_me_write/
    ).test(node.value)
  ) {
    const owner = [...ancestors]
      .reverse()
      .find(
        (parent) =>
          parent.type === 'FunctionDeclaration' ||
          (parent.type === 'VariableDeclarator' &&
            ['ArrowFunctionExpression', 'FunctionExpression'].includes(parent.init?.type)),
      )
    if (owner) {
      const record = selected.get(owner.start) ?? { node: owner, seeds: [] }
      record.seeds.push(node.value)
      selected.set(owner.start, record)
    }
  }
  for (const value of Object.values(node)) {
    if (Array.isArray(value)) {
      for (const child of value) walk(child, [...ancestors, node])
    } else if (value && typeof value === 'object') {
      walk(value, [...ancestors, node])
    }
  }
}
walk(ast)
if (selected.size === 0) throw new Error('No matching practice components found')
fs.mkdirSync(output)
const records = [...selected.values()].map(({ node, seeds }) => ({
  name: node.id?.name,
  byteRange: [
    Buffer.byteLength(source.slice(0, node.start)),
    Buffer.byteLength(source.slice(0, node.end)),
  ],
  seeds: [...new Set(seeds)],
  source: ['ArrowFunctionExpression', 'FunctionExpression'].includes(node.type)
    ? `const timing_${node.start} = ${source.slice(node.start, node.end)};`
    : node.type === 'VariableDeclarator'
      ? `const ${source.slice(node.start, node.end)};`
      : source.slice(node.start, node.end),
}))
fs.writeFileSync(
  path.join(output, 'practice.mjs'),
  records.map((record) => record.source).join('\n'),
)
fs.writeFileSync(
  path.join(output, 'provenance.json'),
  JSON.stringify(
    {
      archive,
      member,
      sha256: digest,
      transformation:
        'Exact enclosing function declarations or variable declarators selected by decoded AST string literals; const prefix and semicolon for declarators; joined with newlines; never executed.',
      declarations: records.map(({ source: omitted, ...record }) => record),
    },
    null,
    2,
  ),
)
console.log(
  JSON.stringify(
    records.map(({ source: omitted, ...record }) => record),
    null,
    2,
  ),
)

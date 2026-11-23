import fs from 'node:fs'
import crypto from 'node:crypto'
import path from 'node:path'
import { parse } from '@babel/parser'

const archive = '/Applications/Typeless.app/Contents/Resources/app.asar'
const member = 'dist/renderer/static/js/Dx8DUWFx.js'
const output = process.argv[2]
if (!output || fs.existsSync(output)) throw new Error('An absent output directory is required')
const descriptor = fs.openSync(archive, 'r')
const prefix = Buffer.alloc(16)
fs.readSync(descriptor, prefix, 0, 16, 0)
const header = Buffer.alloc(prefix.readUInt32LE(12))
fs.readSync(descriptor, header, 0, header.length, 16)
let entry = JSON.parse(header.toString())
for (const segment of member.split('/')) entry = entry.files[segment]
const bytes = Buffer.alloc(entry.size)
fs.readSync(descriptor, bytes, 0, bytes.length, 8 + prefix.readUInt32LE(4) + Number(entry.offset))
fs.closeSync(descriptor)
const sha256 = crypto.createHash('sha256').update(bytes).digest('hex')
if (sha256 !== '82f877388db8af27aa8c71541e21d4d3612a333443c7195165f9cf3ecde58288')
  throw new Error('Reference member identity changed')
const source = bytes.toString()
const ast = parse(source, { sourceType: 'module' })
const declarations = []
for (const statement of ast.program.body) {
  if (statement.type !== 'VariableDeclaration') continue
  for (const declaration of statement.declarations) {
    if (!['Dt', 'So', 'yo'].includes(declaration.id?.name)) continue
    declarations.push({
      name: declaration.id.name,
      start: declaration.start,
      end: declaration.end,
      source: source.slice(declaration.start, declaration.end),
    })
  }
}
if (declarations.length !== 3) throw new Error('Shortcut editor declarations unavailable')
fs.mkdirSync(output)
fs.writeFileSync(
  path.join(output, 'shortcut-editor.mjs'),
  declarations.map((declaration) => `const ${declaration.source};`).join('\n'),
)
fs.writeFileSync(
  path.join(output, 'provenance.json'),
  JSON.stringify(
    {
      archive,
      member,
      sha256,
      declarations: declarations.map(({ name, start, end }) => ({
        name,
        characterRange: [start, end],
        byteRange: [
          Buffer.byteLength(source.slice(0, start)),
          Buffer.byteLength(source.slice(0, end)),
        ],
      })),
    },
    null,
    2,
  ),
)

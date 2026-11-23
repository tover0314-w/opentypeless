import { lstatSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export function selectWindowsReleaseArtifacts(directory, version) {
  const invalid = () => new Error('Windows installer selection failed')
  if (typeof version !== 'string' || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) throw invalid()
  const expected = new Map([
    [`OpenTypeless_${version}_x64_en-US.msi`, 'msi'],
    [`OpenTypeless_${version}_x64-setup.exe`, 'nsis'],
  ])
  const selected = {}
  let count = 0
  function visit(path, depth) {
    if (++count > 1000 || depth > 8) throw invalid()
    const metadata = lstatSync(path)
    if (metadata.isSymbolicLink()) throw invalid()
    if (metadata.isDirectory()) {
      for (const name of readdirSync(path)) visit(join(path, name), depth + 1)
    } else if (/\.(exe|msi)$/i.test(path)) {
      const name = path.split(/[\\/]/).at(-1)
      const kind = expected.get(name)
      if (!metadata.isFile() || metadata.size === 0 || !kind || selected[kind]) throw invalid()
      selected[kind] = path
    }
  }
  const root = resolve(directory)
  if (!lstatSync(root).isDirectory()) throw invalid()
  visit(root, 0)
  if (!selected.msi || !selected.nsis) throw invalid()
  return selected
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    console.log(JSON.stringify(selectWindowsReleaseArtifacts(process.argv[2], process.argv[3])))
  } catch {
    console.error('Windows installer selection failed')
    process.exitCode = 1
  }
}

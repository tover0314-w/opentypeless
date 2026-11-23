import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export function validateReleaseVersion(root, tag) {
  const invalid = () => new Error('Release tag and application versions must match')
  if (typeof tag !== 'string' || tag.trim() !== tag) throw invalid()
  const version = tag.startsWith('v') ? tag.slice(1) : tag
  const pattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/
  if (!pattern.test(version)) throw invalid()
  const packageVersion = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8')).version
  const npmLock = JSON.parse(readFileSync(resolve(root, 'package-lock.json'), 'utf8'))
  const tauriVersion = JSON.parse(readFileSync(resolve(root, 'src-tauri/tauri.conf.json'), 'utf8')).version
  const cargo = readFileSync(resolve(root, 'src-tauri/Cargo.toml'), 'utf8')
  const packageSection = cargo.split(/^\[package\]\s*$/m)[1]?.split(/^\[/m)[0]
  const versions = [...(packageSection ?? '').matchAll(/^version\s*=\s*"([^"]+)"\s*$/gm)]
  const cargoLock = readFileSync(resolve(root, 'src-tauri/Cargo.lock'), 'utf8')
  const lockedPackages = cargoLock.split(/^\[\[package\]\]\s*$/m).slice(1)
    .filter((section) => /^name\s*=\s*"opentypeless"\s*$/m.test(section))
  const lockedVersions = [...(lockedPackages[0] ?? '').matchAll(/^version\s*=\s*"([^"]+)"\s*$/gm)]
  if (packageVersion !== version || tauriVersion !== version ||
      npmLock.version !== version || npmLock.packages?.['']?.version !== version ||
      lockedPackages.length !== 1 || lockedVersions.length !== 1 ||
      lockedVersions[0][1] !== version ||
      versions.length !== 1 || versions[0][1] !== version) throw invalid()
  return version
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    console.log(validateReleaseVersion(process.cwd(), process.argv[2]))
  } catch {
    console.error('Release version preflight failed')
    process.exitCode = 1
  }
}

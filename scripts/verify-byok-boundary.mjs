import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const failures = []
const fail = (message) => failures.push(message)
const read = (path) => readFileSync(path, 'utf8')

for (const path of [
  'src/components/AccountPage/index.tsx',
  'src/components/UpgradePage/index.tsx',
  'src/stores/authStore.ts',
  'src/lib/auth-client.ts',
  'src/lib/cloud-session.ts',
  'src/lib/subscription-refresh-policy.ts',
  'src-tauri/src/stt/cloud.rs',
  'src-tauri/src/llm/cloud.rs',
  'src-tauri/src/stt/managed_audio.rs',
]) {
  if (existsSync(path)) fail(`commercial module still exists: ${path}`)
}

const packageJson = JSON.parse(read('package.json'))
const packageNames = {
  ...packageJson.dependencies,
  ...packageJson.devDependencies,
}
for (const dependency of [
  'better-auth',
  '@tauri-apps/plugin-deep-link',
  '@tauri-apps/plugin-updater',
  '@tauri-apps/plugin-process',
]) {
  if (dependency in packageNames) fail(`removed runtime dependency returned: ${dependency}`)
}

const productionSources = [
  read('src/App.tsx'),
  read('src/lib/constants.ts'),
  read('src-tauri/Cargo.toml'),
  read('src-tauri/tauri.conf.json'),
  read('src-tauri/src/lib.rs'),
  read('src-tauri/src/pipeline.rs'),
  read('src-tauri/src/commands/ask.rs'),
]
const forbiddenPatterns = [
  /www\.opentypeless\.com\/api/i,
  /SessionTokenStore/,
  /ManagedSttCapability/,
  /CloudSessionInvalid/,
  /tauri[_-]plugin[_-](?:deep[_-]link|updater)/i,
  /value:\s*['"]cloud['"]/,
  /#\/(?:account|upgrade)/,
]
for (const pattern of forbiddenPatterns) {
  if (productionSources.some((source) => pattern.test(source))) {
    fail(`forbidden production pattern found: ${pattern}`)
  }
}

for (const name of readdirSync('src/i18n/locales').filter((name) => name.endsWith('.json'))) {
  const locale = JSON.parse(read(join('src/i18n/locales', name)))
  for (const key of ['account', 'upgrade', 'updates']) {
    if (key in locale) fail(`${name}: top-level ${key} copy still exists`)
  }
  if (locale.providers?.stt?.cloud || locale.providers?.llm?.cloud) {
    fail(`${name}: managed cloud provider copy still exists`)
  }
}

if (failures.length > 0) {
  console.error(`BYOK boundary verification failed:\n- ${failures.join('\n- ')}`)
  process.exit(1)
}

console.log('BYOK boundary verified: commercial runtime modules and provider entries are absent.')

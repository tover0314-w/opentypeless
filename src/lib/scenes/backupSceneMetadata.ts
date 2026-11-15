import type {
  ActiveScene,
  ContextFamily,
  FamilySceneAssignment,
  SystemSceneOverride,
} from '../../stores/appStore'

const FAMILIES: readonly ContextFamily[] = [
  'email',
  'work_chat',
  'personal_chat',
  'document',
  'project_management',
  'developer_collaboration',
  'prompt_or_code',
  'support',
  'social',
  'general',
]

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function text(value: unknown, maxLength: number): string {
  return typeof value === 'string'
    ? Array.from(value.replace(/\0/g, '').trim()).slice(0, maxLength).join('')
    : ''
}

export function restoreActiveScene(value: unknown): ActiveScene | undefined {
  if (!record(value)) return undefined
  const id = text(value.id, 120)
  const name = text(value.name, 80)
  const prompt = text(value.prompt_template, 4000)
  const source = value.source
  if (
    !id ||
    !name ||
    !prompt ||
    (source !== 'custom' && source !== 'builtin' && source !== 'cloud')
  ) {
    return undefined
  }
  return { id, name, prompt_template: prompt, source }
}

export function restoreSystemOverrides(value: unknown[]): SystemSceneOverride[] {
  const seen = new Set<string>()
  const restored: SystemSceneOverride[] = []
  for (const item of value.slice(0, 100)) {
    if (!record(item)) continue
    const id = text(item.id, 120)
    const prompt = text(item.prompt_template, 4000)
    if (!FAMILIES.some((family) => family !== 'general' && id === `system_${family}`)) continue
    if (!prompt || seen.has(id)) continue
    seen.add(id)
    restored.push({ id, prompt_template: prompt })
  }
  return restored
}

export function restoreFamilyAssignments(value: unknown[]): FamilySceneAssignment[] {
  const seen = new Set<string>()
  const restored: FamilySceneAssignment[] = []
  for (const item of value.slice(0, 100)) {
    if (!record(item) || !FAMILIES.includes(item.family as ContextFamily)) continue
    const sceneId = text(item.scene_id, 120)
    if (!sceneId || seen.has(String(item.family))) continue
    seen.add(String(item.family))
    restored.push({ family: item.family as ContextFamily, scene_id: sceneId })
  }
  return restored
}

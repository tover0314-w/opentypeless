import type { CustomScene } from '../../stores/appStore'

const MAX_SCENES = 100
const SCENE_ID_MAX_CHARS = 120
const SCENE_NAME_MAX_CHARS = 80
const SCENE_DESCRIPTION_MAX_CHARS = 240
const SCENE_PROMPT_MAX_CHARS = 4000

export interface SceneExportPayload {
  version: 1
  exportedAt: string
  scenes: PortableScene[]
}

export interface PortableScene {
  id: string
  name: string
  description: string
  promptTemplate: string
  source: 'custom'
  createdAt: string
  updatedAt: string
}

export interface SceneImportOptions {
  existingIds: Set<string>
  createId: () => string
  nowIso: () => string
}

export interface SceneImportResult {
  scenes: CustomScene[]
  skipped: number
  report: SceneImportReport
}

export interface SceneImportReport {
  totalRecords: number
  imported: number
  skippedInvalid: number
  skippedLimit: number
  renamedConflicts: number
}

type UnknownSceneRecord = Record<string, unknown>

interface PortableSceneImport {
  scene: CustomScene
  renamedConflict: boolean
}

export function serializeCustomScenes(
  scenes: CustomScene[],
  exportedAt = new Date().toISOString(),
): string {
  const payload: SceneExportPayload = {
    version: 1,
    exportedAt,
    scenes: scenes.map((scene) => ({
      id: scene.id,
      name: scene.name,
      description: scene.description,
      promptTemplate: scene.prompt_template,
      source: 'custom',
      createdAt: scene.created_at,
      updatedAt: scene.updated_at,
    })),
  }

  return JSON.stringify(payload, null, 2)
}

export function importCustomScenesJson(
  json: string,
  options: SceneImportOptions,
): SceneImportResult {
  const records = parseSceneRecords(json)
  const usedIds = new Set(options.existingIds)
  const scenes: CustomScene[] = []
  let skippedInvalid = 0
  let renamedConflicts = 0

  for (const record of records.slice(0, MAX_SCENES)) {
    if (!isRecord(record)) {
      skippedInvalid += 1
      continue
    }
    const imported = portableRecordToCustomScene(record, usedIds, options)
    if (!imported) {
      skippedInvalid += 1
      continue
    }
    const { scene, renamedConflict } = imported
    usedIds.add(scene.id)
    if (renamedConflict) renamedConflicts += 1
    scenes.push(scene)
  }

  const skippedLimit = Math.max(0, records.length - MAX_SCENES)
  const skipped = skippedInvalid + skippedLimit
  return {
    scenes,
    skipped,
    report: {
      totalRecords: records.length,
      imported: scenes.length,
      skippedInvalid,
      skippedLimit,
      renamedConflicts,
    },
  }
}

function parseSceneRecords(json: string): unknown[] {
  const parsed = JSON.parse(json) as unknown
  if (isRecord(parsed) && 'version' in parsed && parsed.version !== 1) {
    throw new Error('Unsupported scene pack version')
  }
  if (Array.isArray(parsed)) return parsed
  if (isRecord(parsed) && Array.isArray(parsed.scenes)) return parsed.scenes
  throw new Error('Invalid scene pack structure')
}

function portableRecordToCustomScene(
  record: UnknownSceneRecord,
  usedIds: Set<string>,
  options: SceneImportOptions,
): PortableSceneImport | null {
  const name = sanitizeSceneString(readString(record.name), SCENE_NAME_MAX_CHARS)
  const description = sanitizeSceneString(
    readString(record.description),
    SCENE_DESCRIPTION_MAX_CHARS,
  )
  const promptTemplate = sanitizeSceneString(
    readString(record.promptTemplate) || readString(record.prompt_template),
    SCENE_PROMPT_MAX_CHARS,
  )

  if (!name || !promptTemplate) {
    return null
  }

  const importedId = sanitizeSceneString(readString(record.id), SCENE_ID_MAX_CHARS)
  const { id, renamedConflict } = uniqueSceneId(importedId, usedIds, options.createId)
  const timestamp = options.nowIso()
  const createdAt =
    sanitizeSceneString(
      readString(record.createdAt) || readString(record.created_at),
      SCENE_ID_MAX_CHARS,
    ) || timestamp
  const updatedAt =
    sanitizeSceneString(
      readString(record.updatedAt) || readString(record.updated_at),
      SCENE_ID_MAX_CHARS,
    ) || timestamp

  return {
    scene: {
      id,
      name,
      description,
      prompt_template: promptTemplate,
      created_at: createdAt,
      updated_at: updatedAt,
    },
    renamedConflict,
  }
}

function uniqueSceneId(
  importedId: string,
  usedIds: Set<string>,
  createId: () => string,
): { id: string; renamedConflict: boolean } {
  if (importedId && !usedIds.has(importedId)) {
    return { id: importedId, renamedConflict: false }
  }

  for (let attempt = 0; attempt < MAX_SCENES; attempt += 1) {
    const nextId = sanitizeSceneString(createId(), SCENE_ID_MAX_CHARS)
    if (nextId && !usedIds.has(nextId)) {
      return { id: nextId, renamedConflict: Boolean(importedId) }
    }
  }
  throw new Error('Could not generate a unique scene ID')
}

function sanitizeSceneString(value: string, maxChars: number): string {
  return Array.from(value.replace(/\0/g, '').trim()).slice(0, maxChars).join('')
}

function readString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function isRecord(value: unknown): value is UnknownSceneRecord {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

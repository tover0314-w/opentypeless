import { describe, expect, it } from 'vitest'
import type { CustomScene } from '../../../stores/appStore'
import { importCustomScenesJson, serializeCustomScenes } from '../sceneImportExport'

const existingScene: CustomScene = {
  id: 'custom_existing',
  name: 'Existing',
  description: 'Already present',
  prompt_template: 'Keep as-is.',
  created_at: '2026-07-01T00:00:00.000Z',
  updated_at: '2026-07-01T00:00:00.000Z',
}

describe('scene import/export', () => {
  it.each([null, false, 'invalid', {}, { scenes: {} }, { version: 1 }])(
    'rejects malformed scene pack envelopes',
    (pack) => {
      expect(() =>
        importCustomScenesJson(JSON.stringify(pack), {
          existingIds: new Set(),
          createId: () => 'unused',
          nowIso: () => '2026-11-04',
        }),
      ).toThrow('Invalid scene pack structure')
    },
  )

  it.each([[], { scenes: [] }, { version: 1, scenes: [] }])(
    'accepts supported empty scene packs',
    (pack) => {
      const result = importCustomScenesJson(JSON.stringify(pack), {
        existingIds: new Set(),
        createId: () => 'unused',
        nowIso: () => '2026-11-04',
      })
      expect(result.scenes).toEqual([])
      expect(result.report.totalRecords).toBe(0)
    },
  )

  it('fails after bounded ID collisions rather than looping indefinitely', () => {
    let attempts = 0
    expect(() =>
      importCustomScenesJson(JSON.stringify([existingScene]), {
        existingIds: new Set([existingScene.id]),
        createId: () => {
          attempts += 1
          if (attempts > 100) throw new Error('Generator exceeded test budget')
          return existingScene.id
        },
        nowIso: () => '2026-11-04',
      }),
    ).toThrow('Could not generate a unique scene ID')
    expect(attempts).toBe(100)
  })

  it('reports non-object records instead of silently removing them from totals', () => {
    const result = importCustomScenesJson(
      JSON.stringify({ scenes: [null, 'invalid', 12, [], existingScene] }),
      { existingIds: new Set(), createId: () => 'unused', nowIso: () => '2026-11-04' },
    )
    expect(result.scenes).toEqual([existingScene])
    expect(result.report).toEqual({
      totalRecords: 5,
      imported: 1,
      skippedInvalid: 4,
      skippedLimit: 0,
      renamedConflicts: 0,
    })
    expect(result.skipped).toBe(4)
  })

  it.each([2, 0, '1', null])('rejects an unsupported declared scene version %s', (version) => {
    expect(() =>
      importCustomScenesJson(JSON.stringify({ version, scenes: [existingScene] }), {
        existingIds: new Set(),
        createId: () => 'unused',
        nowIso: () => '2026-11-04',
      }),
    ).toThrow('Unsupported scene pack version')
  })

  it.each([[existingScene], { scenes: [existingScene] }, { version: 1, scenes: [existingScene] }])(
    'preserves supported versioned and legacy packs',
    (pack) => {
      const result = importCustomScenesJson(JSON.stringify(pack), {
        existingIds: new Set(),
        createId: () => 'unused',
        nowIso: () => '2026-11-04',
      })
      expect(result.scenes).toEqual([existingScene])
    },
  )

  it('bounds imported text by Unicode characters without splitting surrogate pairs', () => {
    const result = importCustomScenesJson(
      JSON.stringify({
        scenes: [
          {
            id: 'unicode',
            name: `${'a'.repeat(79)}😀tail`,
            description: `${'b'.repeat(239)}𠮷tail`,
            promptTemplate: `${'c'.repeat(3999)}😀tail`,
          },
        ],
      }),
      { existingIds: new Set(), createId: () => 'unused', nowIso: () => '2026-11-04' },
    )
    expect(result.scenes[0].name).toBe(`${'a'.repeat(79)}😀`)
    expect(result.scenes[0].description).toBe(`${'b'.repeat(239)}𠮷`)
    expect(result.scenes[0].prompt_template).toBe(`${'c'.repeat(3999)}😀`)
  })

  it('round trips a full supported scene pack below the file size limit', () => {
    const scenes = Array.from({ length: 100 }, (_, index) => ({
      ...existingScene,
      id: `custom_${index}`,
      name: '名'.repeat(80),
      description: '述'.repeat(240),
      prompt_template: '文'.repeat(4000),
    }))
    const json = serializeCustomScenes(scenes, '2026-11-04T00:00:00.000Z')
    expect(new TextEncoder().encode(json).length).toBeLessThan(5 * 1024 * 1024)
    const imported = importCustomScenesJson(json, {
      existingIds: new Set(),
      createId: () => 'unused',
      nowIso: () => '2026-11-04T00:00:00.000Z',
    })
    expect(imported.scenes).toEqual(scenes)
    expect(imported.skipped).toBe(0)
  })

  it('serializes custom scenes into a portable versioned JSON payload', () => {
    const json = serializeCustomScenes([existingScene], '2026-07-06T00:00:00.000Z')
    const payload = JSON.parse(json)

    expect(payload.version).toBe(1)
    expect(payload.exportedAt).toBe('2026-07-06T00:00:00.000Z')
    expect(payload.scenes).toEqual([
      expect.objectContaining({
        id: 'custom_existing',
        name: 'Existing',
        description: 'Already present',
        promptTemplate: 'Keep as-is.',
        source: 'custom',
      }),
    ])
  })

  it('imports style-pack JSON without overwriting existing scene ids', () => {
    const input = JSON.stringify({
      version: 1,
      scenes: [
        {
          id: 'custom_existing',
          name: ' Imported support reply ',
          description: ' Reply template ',
          promptTemplate: ' Write a concise support response. ',
        },
      ],
    })

    const result = importCustomScenesJson(input, {
      existingIds: new Set(['custom_existing']),
      createId: () => 'custom_imported_1',
      nowIso: () => '2026-07-06T01:00:00.000Z',
    })

    expect(result.skipped).toBe(0)
    expect(result.scenes).toEqual([
      {
        id: 'custom_imported_1',
        name: 'Imported support reply',
        description: 'Reply template',
        prompt_template: 'Write a concise support response.',
        created_at: '2026-07-06T01:00:00.000Z',
        updated_at: '2026-07-06T01:00:00.000Z',
      },
    ])
  })

  it('skips invalid scenes and bounds imported field sizes', () => {
    const input = JSON.stringify({
      version: 1,
      scenes: [
        { id: 'empty_prompt', name: 'No prompt', promptTemplate: '   ' },
        {
          id: 'valid',
          name: `  Name\0${'x'.repeat(120)}  `,
          description: `  Desc\0${'y'.repeat(300)}  `,
          promptTemplate: `  Prompt\0${'z'.repeat(5000)}  `,
        },
      ],
    })

    const result = importCustomScenesJson(input, {
      existingIds: new Set(),
      createId: () => 'custom_unused',
      nowIso: () => '2026-07-06T02:00:00.000Z',
    })

    expect(result.skipped).toBe(1)
    expect(result.scenes).toHaveLength(1)
    expect(result.scenes[0].id).toBe('valid')
    expect(result.scenes[0].name).not.toContain('\0')
    expect(result.scenes[0].name).toHaveLength(80)
    expect(result.scenes[0].description).toHaveLength(240)
    expect(result.scenes[0].prompt_template).toHaveLength(4000)
  })

  it('reports import counts, renamed conflicts, invalid rows, and limit skips', () => {
    const validScenes = Array.from({ length: 101 }, (_, index) => ({
      id: `custom_valid_${index}`,
      name: `Valid ${index}`,
      promptTemplate: `Use style ${index}.`,
    }))
    const input = JSON.stringify({
      version: 1,
      scenes: [
        {
          id: 'custom_existing',
          name: 'Conflicting scene',
          promptTemplate: 'Keep this imported scene.',
        },
        { id: 'invalid', name: 'Invalid scene', promptTemplate: '   ' },
        ...validScenes,
      ],
    })

    const result = importCustomScenesJson(input, {
      existingIds: new Set(['custom_existing']),
      createId: () => 'custom_imported_1',
      nowIso: () => '2026-07-06T03:00:00.000Z',
    })

    expect(result.skipped).toBe(4)
    expect(result.report).toEqual({
      totalRecords: 103,
      imported: 99,
      skippedInvalid: 1,
      skippedLimit: 3,
      renamedConflicts: 1,
    })
  })
})

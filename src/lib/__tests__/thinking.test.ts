import { describe, it, expect } from 'vitest'
import registry from '../thinking-capabilities.json'
import {
  isThinkingStrength,
  thinkingCapability,
  thinkingState,
  type ThinkingStrength,
} from '../thinking'
import { createBackupSettings, mergeBackupSettings } from '../backup-settings'
import { useAppStore, type AppConfig } from '../../stores/appStore'

describe('thinking capability registry and persisted intent', () => {
  it('uses exactly the shared documented options for every listed model and route', () => {
    for (const entry of registry.entries) {
      expect(new Set(entry.options).size).toBe(entry.options.length)
      for (const option of entry.options) expect(isThinkingStrength(option)).toBe(true)
      for (const provider of entry.providers)
        for (const model of entry.models)
          for (const path of entry.paths) {
            const base = `https://${entry.host}${path}`
            expect(thinkingCapability(provider, base, model)?.id).toBe(entry.id)
            expect(thinkingState(provider, `${base}/`, model).options).toEqual(entry.options)
          }
    }
  })
  it('does not infer future snapshots, proxies, native Gemini or unsupported routes', () => {
    for (const [provider, base, model] of [
      ['openai', 'https://api.openai.com/v1', 'gpt-5.1-2099-01-01'],
      ['openai', 'https://proxy.example/v1', 'gpt-5.1'],
      ['openai', 'http://api.openai.com/v1', 'gpt-5.1'],
      ['openai', 'https://api.openai.com:8443/v1', 'gpt-5.1'],
      ['openai', 'https://api.openai.com/v1/responses', 'gpt-5.1'],
      ['openai', 'https://api.openai.com/v1?', 'gpt-5.1'],
      ['openai', 'https://api.openai.com/v1#', 'gpt-5.1'],
      ['custom', 'https://api.openai.com/v1', 'gpt-5.1'],
      ['gemini', 'https://generativelanguage.googleapis.com/v1beta', 'gemini-2.5-flash'],
      ['deepseek', 'https://api.deepseek.com/anthropic', 'deepseek-flash'],
      ['openrouter', 'https://openrouter.ai/api/v1', 'openai/gpt-5.1'],
      ['cloud', 'https://api.openai.com/v1', 'gpt-5.1'],
    ]) {
      expect(thinkingState(provider, base, model, 'low')).toEqual({
        options: ['default'],
        supported: false,
        available: false,
      })
    }
  })
  it('never treats low or minimal as off', () => {
    expect(thinkingState('openai', 'https://api.openai.com/v1', 'gpt-5').options).not.toContain(
      'off',
    )
    expect(
      thinkingState('openai', 'https://api.openai.com/v1', 'gpt-6-astra').options,
    ).not.toContain('off')
    expect(
      thinkingState(
        'gemini',
        'https://generativelanguage.googleapis.com/v1beta/openai',
        'gemini-2.5-pro',
      ).options,
    ).not.toContain('off')
    expect(
      thinkingState('claude', 'https://api.anthropic.com/v1', 'claude-opus-5-5').options,
    ).not.toContain('off')
    expect(
      thinkingState('claude', 'https://api.anthropic.com/v1', 'claude-sonnet-5-5').options,
    ).not.toContain('off')
  })
  it('retains a saved unsupported choice and restores its effective state when switching back', () => {
    const original = useAppStore.getState().config
    try {
      useAppStore.getState().updateConfig({
        llm_provider: 'deepseek',
        llm_base_url: 'https://api.deepseek.com/v1',
        llm_model: 'deepseek-flash',
        llm_thinking_strength: 'max',
      })
      useAppStore.getState().updateConfig({ llm_model: 'future' })
      let c = useAppStore.getState().config
      expect(c.llm_thinking_strength).toBe('max')
      expect(
        thinkingState(c.llm_provider, c.llm_base_url, c.llm_model, c.llm_thinking_strength)
          .supported,
      ).toBe(false)
      useAppStore.getState().updateConfig({ llm_model: 'deepseek-flash' })
      c = useAppStore.getState().config
      expect(
        thinkingState(c.llm_provider, c.llm_base_url, c.llm_model, c.llm_thinking_strength)
          .supported,
      ).toBe(true)
    } finally {
      useAppStore.getState().setConfig(original)
    }
  })
  it('defaults an old loaded config instead of inheriting an unrelated current choice', () => {
    const original = useAppStore.getState().config
    try {
      useAppStore.getState().updateConfig({ llm_thinking_strength: 'high' })
      const old = { ...original } as Partial<AppConfig>
      delete old.llm_thinking_strength
      useAppStore.getState().setConfig(old as AppConfig)
      expect(useAppStore.getState().config.llm_thinking_strength).toBe('default')
    } finally {
      useAppStore.getState().setConfig(original)
    }
  })
  it('exports and restores each value; old backups use default and malformed values are rejected', () => {
    const original = useAppStore.getState().config
    const choices: ThinkingStrength[] = [
      'default',
      'off',
      'minimal',
      'low',
      'medium',
      'high',
      'xhigh',
      'max',
      'budget_1024',
      'budget_2048',
    ]
    for (const choice of choices) {
      const config = { ...original, llm_thinking_strength: choice }
      const backup = JSON.parse(JSON.stringify(createBackupSettings(config)))
      expect(backup.llm_thinking_strength).toBe(choice)
      expect(mergeBackupSettings(original, backup).llm_thinking_strength).toBe(choice)
    }
    const current = { ...original, llm_thinking_strength: 'high' as const }
    expect(mergeBackupSettings(current, { llm_model: 'legacy-model' }).llm_thinking_strength).toBe(
      'default',
    )
    expect(
      mergeBackupSettings(current, { llm_thinking_strength: 'invented' }).llm_thinking_strength,
    ).toBe('high')
  })
})

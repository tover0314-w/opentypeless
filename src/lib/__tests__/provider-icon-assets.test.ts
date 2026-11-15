import { describe, expect, it } from 'vitest'
import sources from '../../../docs/provider-icon-sources.md?raw'
import notices from '../../../THIRD_PARTY_NOTICES.md?raw'

const assets = import.meta.glob<string>('../../assets/provider-icons/*.svg', {
  query: '?raw',
  import: 'default',
  eager: true,
})

describe('bundled provider SVG assets', () => {
  it('includes the new provider asset sources in the distributed notices', () => {
    expect(notices).toContain('## SVG Logos provider marks')
    for (const icon of ['openai-icon', 'openrouter-icon', 'deepseek-icon', 'qwen-icon']) {
      expect(notices).toContain(`logos:${icon}`)
    }
    expect(notices).toContain('Collection licensing does not grant trademark rights.')
    expect(notices).toContain('simple-icons:ollama')
    expect(notices).toContain('simple-icons:deepgram')
  })

  it('examines the expected local assets rather than an empty inventory', () => {
    expect(Object.keys(assets).sort()).toEqual([
      '../../assets/provider-icons/assemblyai.svg',
      '../../assets/provider-icons/deepgram.svg',
      '../../assets/provider-icons/deepseek.svg',
      '../../assets/provider-icons/doubao.svg',
      '../../assets/provider-icons/groq.svg',
      '../../assets/provider-icons/moonshot.svg',
      '../../assets/provider-icons/ollama.svg',
      '../../assets/provider-icons/openai.svg',
      '../../assets/provider-icons/openrouter.svg',
      '../../assets/provider-icons/qwen.svg',
      '../../assets/provider-icons/siliconflow.svg',
      '../../assets/provider-icons/volcengine.svg',
      '../../assets/provider-icons/zhipu.svg',
    ])
  })

  it.each(Object.entries(assets))(
    'keeps %s passive and matches its documented digest',
    async (path, source) => {
      const document = new DOMParser().parseFromString(source, 'image/svg+xml')
      expect(document.querySelector('parsererror')).toBeNull()
      expect(document.documentElement.localName).toBe('svg')
      expect(document.documentElement.getAttribute('viewBox')).toBeTruthy()
      expect(
        document.querySelector('script, foreignObject, image, use, style, animate, set'),
      ).toBeNull()
      for (const element of document.querySelectorAll('*')) {
        for (const attribute of element.attributes) {
          expect(attribute.name).not.toMatch(/^on/i)
          expect(attribute.name).not.toMatch(/href$/i)
          expect(attribute.value).not.toMatch(/url\s*\(/i)
        }
      }
      const bytes = new TextEncoder().encode(source)
      const digest = await crypto.subtle.digest('SHA-256', bytes)
      const hex = Array.from(new Uint8Array(digest), (byte) =>
        byte.toString(16).padStart(2, '0'),
      ).join('')
      const filename = path.split('/').pop()
      const row = sources.split('\n').find((line) => line.includes(`/${filename}\``))
      expect(row).toContain(hex)
      expect(row).toMatch(/\| (CC0-1\.0|MIT) \|/)
    },
  )
})

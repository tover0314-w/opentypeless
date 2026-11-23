import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { ProviderLogo } from '../ProviderLogo'
import { STT_PROVIDERS, LLM_PROVIDERS } from '../../../lib/constants'
import openrouter from '../../../assets/provider-icons/openrouter.svg'
import openai from '../../../assets/provider-icons/openai.svg'
import deepseek from '../../../assets/provider-icons/deepseek.svg'
import qwen from '../../../assets/provider-icons/qwen.svg'
import ollama from '../../../assets/provider-icons/ollama.svg'
import deepgram from '../../../assets/provider-icons/deepgram.svg'
import assemblyai from '../../../assets/provider-icons/assemblyai.svg'
import groq from '../../../assets/provider-icons/groq.svg'
import siliconflow from '../../../assets/provider-icons/siliconflow.svg'
import volcengine from '../../../assets/provider-icons/volcengine.svg'
import doubao from '../../../assets/provider-icons/doubao.svg'
import zhipu from '../../../assets/provider-icons/zhipu.svg'
import moonshot from '../../../assets/provider-icons/moonshot.svg'

afterEach(cleanup)

describe('provider marks', () => {
  it('uses the local monochrome Moonshot mark', () => {
    const { container } = render(<ProviderLogo provider="moonshot" />)
    expect(container.querySelector('img')).toHaveAttribute('src', moonshot)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
  })

  it.each(['zhipu', 'glm-asr'])('shares the local Zhipu mark for %s', (provider) => {
    const { container } = render(<ProviderLogo provider={provider} />)
    expect(container.querySelector('img')).toHaveAttribute('src', zhipu)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
  })

  it('distinguishes the Doubao model mark from the Volcengine speech mark', () => {
    const { container } = render(<ProviderLogo provider="doubao" />)
    expect(container.querySelector('img')).toHaveAttribute('src', doubao)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
    expect(container.querySelector('img')).not.toHaveAttribute('src', volcengine)
  })

  it('uses the local Volcengine mark for its speech provider', () => {
    const { container } = render(<ProviderLogo provider="volcengine-doubao" />)
    expect(container.querySelector('img')).toHaveAttribute('src', volcengine)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
  })

  it('uses the local SiliconCloud mark for SiliconFlow', () => {
    const { container } = render(<ProviderLogo provider="siliconflow" />)
    expect(container.querySelector('img')).toHaveAttribute('src', siliconflow)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
  })

  it.each(['groq', 'groq-whisper'])('shares the local monochrome Groq mark for %s', (provider) => {
    const { container } = render(<ProviderLogo provider={provider} />)
    expect(container.querySelector('img')).toHaveAttribute('src', groq)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
  })

  it('uses the local monochrome AssemblyAI mark', () => {
    const { container } = render(<ProviderLogo provider="assemblyai" />)
    expect(container.querySelector('img')).toHaveAttribute('src', assemblyai)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
  })

  it('uses a local monochrome Deepgram symbol', () => {
    const { container } = render(<ProviderLogo provider="deepgram" />)
    expect(container.querySelector('img')).toHaveAttribute('src', deepgram)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
  })

  it('uses the local monochrome Ollama mark', () => {
    const { container } = render(<ProviderLogo provider="ollama" />)
    expect(container.querySelector('img')).toHaveAttribute('src', ollama)
    expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
  })

  it.each(['qwen', 'aliyun-qwen3-asr'])('shares the local Qwen mark for %s', (provider) => {
    const { container } = render(<ProviderLogo provider={provider} />)
    expect(container.querySelector('img')).toHaveAttribute('src', qwen)
  })

  it('uses the local DeepSeek mark without monochrome inversion', () => {
    const { container } = render(<ProviderLogo provider="deepseek" />)
    expect(container.querySelector('img')).toHaveAttribute('src', deepseek)
    expect(container.querySelector('img')).not.toHaveClass('provider-logo-monochrome')
  })

  it.each(['unknown-provider', 'constructor', 'toString', '__proto__'])(
    'uses a neutral fallback for the unsupported provider %s',
    (provider) => {
      const { container } = render(<ProviderLogo provider={provider} />)
      expect(container.querySelector('img')).toBeNull()
      expect(container.querySelector('svg')).not.toBeNull()
    },
  )

  it('uses the locally bundled OpenRouter brand mark', () => {
    const { container } = render(<ProviderLogo provider="openrouter" />)
    expect(container.querySelector('img')).toHaveAttribute('src', openrouter)
  })

  it.each([...STT_PROVIDERS, ...LLM_PROVIDERS])(
    'provides a local decorative mark for $value',
    ({ value }) => {
      const { container } = render(<ProviderLogo provider={value} />)
      expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true')
      expect(container.querySelector('img, svg')).not.toBeNull()
      for (const image of container.querySelectorAll('img')) {
        expect(image.src).not.toMatch(/^https?:\/\/(?!localhost)/)
        if (value !== 'cloud') expect(image).toHaveClass('h-[18px]', 'w-[18px]')
      }
    },
  )

  it.each(['openai', 'openai-whisper'])(
    'uses the OpenAI mark for %s instead of ChatGPT',
    (provider) => {
      const { container } = render(<ProviderLogo provider={provider} />)
      expect(container.querySelector('img')).toHaveAttribute('src', openai)
      expect(container.querySelector('img')).toHaveClass('provider-logo-monochrome')
    },
  )
})

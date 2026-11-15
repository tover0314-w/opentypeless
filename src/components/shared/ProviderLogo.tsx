import { AudioLines, Server } from 'lucide-react'
import claude from '../../assets/app-icons/reference/claude.webp'
import gemini from '../../assets/app-icons/reference/gemini.webp'
import openrouter from '../../assets/provider-icons/openrouter.svg'
import openai from '../../assets/provider-icons/openai.svg'
import deepseek from '../../assets/provider-icons/deepseek.svg'
import qwen from '../../assets/provider-icons/qwen.svg'
import ollama from '../../assets/provider-icons/ollama.svg'
import deepgram from '../../assets/provider-icons/deepgram.svg'
import assemblyai from '../../assets/provider-icons/assemblyai.svg'
import groq from '../../assets/provider-icons/groq.svg'
import siliconflow from '../../assets/provider-icons/siliconflow.svg'
import volcengine from '../../assets/provider-icons/volcengine.svg'
import doubao from '../../assets/provider-icons/doubao.svg'
import zhipu from '../../assets/provider-icons/zhipu.svg'
import moonshot from '../../assets/provider-icons/moonshot.svg'
import { AppLogo } from './AppLogo'

const BRAND_ASSETS: Record<string, string> = {
  claude,
  gemini,
  openrouter,
  openai,
  deepseek,
  qwen,
  ollama,
  deepgram,
  assemblyai,
  groq,
  siliconflow,
  doubao,
  zhipu,
  moonshot,
  'glm-asr': zhipu,
  'volcengine-doubao': volcengine,
  'groq-whisper': groq,
  'aliyun-qwen3-asr': qwen,
  'openai-whisper': openai,
}

export function ProviderLogo({ provider }: { provider: string }) {
  const asset = Object.prototype.hasOwnProperty.call(BRAND_ASSETS, provider)
    ? BRAND_ASSETS[provider]
    : undefined
  if (asset) {
    return (
      <img
        src={asset}
        alt=""
        aria-hidden="true"
        width={18}
        height={18}
        draggable={false}
        className={`h-[18px] w-[18px] shrink-0 object-contain ${[openai, ollama, deepgram, assemblyai, groq, siliconflow, volcengine, doubao, zhipu, moonshot].includes(asset) ? 'provider-logo-monochrome' : ''}`}
      />
    )
  }
  if (provider === 'cloud') return <AppLogo size={18} />
  const Icon = provider === 'apple-speech' ? AudioLines : Server
  return <Icon size={18} strokeWidth={1.6} aria-hidden="true" className="text-text-tertiary" />
}

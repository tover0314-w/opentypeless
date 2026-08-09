import type { LucideIcon } from 'lucide-react'
import {
  AppWindow,
  Code2,
  FileText,
  GitPullRequest,
  Headphones,
  ListTodo,
  Mail,
  MessageCircle,
  MessagesSquare,
  Share2,
} from 'lucide-react'

import type { ContextFamily } from '../stores/appStore'

interface Props {
  iconKey: string
  family: ContextFamily
  className?: string
}

const FALLBACK_ICON_BY_FAMILY: Record<ContextFamily, LucideIcon> = {
  email: Mail,
  work_chat: MessagesSquare,
  personal_chat: MessageCircle,
  document: FileText,
  project_management: ListTodo,
  developer_collaboration: GitPullRequest,
  prompt_or_code: Code2,
  support: Headphones,
  social: Share2,
  general: AppWindow,
}

export function AppLogo({ family, className = '' }: Props) {
  const FallbackIcon = FALLBACK_ICON_BY_FAMILY[family] ?? AppWindow
  return (
    <FallbackIcon
      size={15}
      aria-hidden="true"
      className={`h-4 w-4 shrink-0 text-text-tertiary ${className}`}
    />
  )
}

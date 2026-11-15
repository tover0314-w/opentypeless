import { useCallback, useEffect, useRef, useState } from 'react'
import { readCredential, setCredential } from '../lib/tauri'
import { providerErrorMessage } from '../lib/providerErrorMessage'

const credentialQueues = new Map<string, Promise<void>>()

function queueCredentialOperation<Result>(
  namespace: 'stt' | 'llm',
  provider: string,
  operation: () => Promise<Result>,
): Promise<Result> {
  const key = `${namespace}:${provider}`
  const pending = (credentialQueues.get(key) ?? Promise.resolve()).then(operation)
  const tail = pending.then(
    () => undefined,
    () => undefined,
  )
  credentialQueues.set(key, tail)
  void tail.then(() => {
    if (credentialQueues.get(key) === tail) credentialQueues.delete(key)
  })
  return pending
}

interface CredentialDraft {
  namespace: 'stt' | 'llm'
  provider: string
  value: string
  version: number
  dirty: boolean
  active: boolean
  timer: ReturnType<typeof setTimeout> | null
  writes: Promise<void>
}

export function useProviderCredential(
  namespace: 'stt' | 'llm',
  provider: string,
  initialValue: string,
  enabled: boolean,
) {
  const [value, setValue] = useState(enabled ? initialValue : '')
  const [error, setError] = useState<string | null>(null)
  const current = useRef<CredentialDraft | null>(null)

  const save = useCallback((draft: CredentialDraft) => {
    if (draft.timer) clearTimeout(draft.timer)
    draft.timer = null
    if (!draft.dirty) return draft.writes
    const secret = draft.value
    const version = draft.version
    draft.dirty = false
    draft.writes = queueCredentialOperation(draft.namespace, draft.provider, async () => {
      try {
        await setCredential(draft.namespace, draft.provider, secret)
        if (draft.active && version === draft.version) setError(null)
      } catch (caught) {
        if (version === draft.version) draft.dirty = true
        if (draft.active && version === draft.version) {
          setError(
            providerErrorMessage(caught, secret, 'Could not save credential in the system vault'),
          )
        }
        console.error('[credentials] failed to save provider credential')
      }
    })
    return draft.writes
  }, [])

  useEffect(() => {
    setValue(enabled ? initialValue : '')
    setError(null)
    if (!enabled) {
      current.current = null
      return
    }
    const draft: CredentialDraft = {
      namespace,
      provider,
      value: initialValue,
      version: 0,
      dirty: false,
      active: true,
      timer: null,
      writes: Promise.resolve(),
    }
    current.current = draft
    queueCredentialOperation(namespace, provider, () =>
      draft.active ? readCredential(namespace, provider) : Promise.resolve(null),
    )
      .then((secret) => {
        if (!draft.active || draft.version !== 0) return
        draft.value = secret ?? initialValue
        setValue(draft.value)
      })
      .catch(() => {
        if (draft.active && draft.version === 0) {
          setError('Could not read credential from the system vault')
        }
        console.error('[credentials] failed to read provider credential')
      })
    return () => {
      draft.active = false
      void save(draft)
    }
  }, [namespace, provider, initialValue, enabled, save])

  const edit = useCallback(
    (secret: string) => {
      const draft = current.current
      if (!draft?.active) return
      draft.version += 1
      draft.value = secret
      draft.dirty = true
      setValue(secret)
      setError(null)
      if (draft.timer) clearTimeout(draft.timer)
      draft.timer = setTimeout(() => void save(draft), 350)
    },
    [save],
  )

  const flush = useCallback(() => {
    const draft = current.current
    if (draft?.active) return save(draft)
    return Promise.resolve()
  }, [save])

  return { value, error, edit, flush }
}

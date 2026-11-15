import {
  previewDictionaryImport,
  type DictionaryImportFormat,
  type DictionaryImportReport,
} from '../../lib/tauri'

export const MAX_IMPORT_BYTES = 5 * 1024 * 1024
export const MAX_WORD_CHARS = 100

export type ImportPick =
  | {
      ok: true
      fileName: string
      bytes: number[]
      format: DictionaryImportFormat
      report: DictionaryImportReport
    }
  | { ok: false; fileName: string; errorKey: string }

const BACKEND_IMPORT_ERRORS: Record<string, string> = {
  dictionary_import_file_too_large: 'dictionary.importFileTooLarge',
  dictionary_import_invalid_utf8: 'dictionary.importInvalidFile',
  dictionary_import_unsupported_format: 'dictionary.importInvalidFile',
  dictionary_import_invalid_structure: 'dictionary.importInvalidFile',
  dictionary_import_too_many_rows: 'dictionary.importTooMany',
}

function importFormat(fileName: string): DictionaryImportFormat | null {
  const extension = fileName.split('.').pop()?.toLowerCase()
  return extension === 'txt' || extension === 'csv' || extension === 'json' ? extension : null
}

export function importErrorKey(error: unknown) {
  const message = String(error)
  const code = Object.keys(BACKEND_IMPORT_ERRORS).find((key) => message.includes(key))
  return code ? BACKEND_IMPORT_ERRORS[code] : 'dictionary.importFailed'
}

export async function pickImportFile(file: File): Promise<ImportPick> {
  const fileName = file.name
  const format = importFormat(fileName)
  if (!format) return { ok: false, fileName, errorKey: 'dictionary.importInvalidFile' }
  if (file.size > MAX_IMPORT_BYTES) {
    return { ok: false, fileName, errorKey: 'dictionary.importFileTooLarge' }
  }
  let bytes: number[]
  try {
    bytes = Array.from(new Uint8Array(await file.arrayBuffer()))
  } catch {
    return { ok: false, fileName, errorKey: 'dictionary.importReadFailed' }
  }
  try {
    const report = await previewDictionaryImport(bytes, format)
    if (report.accepted === 0) return { ok: false, fileName, errorKey: 'dictionary.importEmpty' }
    return { ok: true, fileName, bytes, format, report }
  } catch (error) {
    return { ok: false, fileName, errorKey: importErrorKey(error) }
  }
}

/** Matches the backend's `chars().count()` limit rather than UTF-16 length. */
export function termErrorKey(term: string) {
  if (!term) return 'dictionary.pleaseEnterWord'
  if ([...term].length > MAX_WORD_CHARS) return 'dictionary.exceedsLength'
  return null
}

export function isDuplicateError(error: unknown) {
  return String(error).includes('dictionary_duplicate')
}

export function downloadText(content: string, format: 'json' | 'csv') {
  const type = format === 'json' ? 'application/json' : 'text/csv;charset=utf-8'
  const url = URL.createObjectURL(new Blob([content], { type }))
  const link = document.createElement('a')
  link.href = url
  link.download = `opentypeless-dictionary-${new Date().toISOString().slice(0, 10)}.${format}`
  link.click()
  URL.revokeObjectURL(url)
}

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Trash2, Plus } from 'lucide-react'
import { useAppStore } from '../../stores/appStore'
import { addDictionaryEntry, removeDictionaryEntry, getDictionary } from '../../lib/tauri'
import { toast } from '../toastApi'

export function DictionaryPane() {
  const dictionary = useAppStore((s) => s.dictionary)
  const setDictionary = useAppStore((s) => s.setDictionary)
  const { t } = useTranslation()
  const [word, setWord] = useState('')
  const [pronunciation, setPronunciation] = useState('')

  const handleAdd = async () => {
    if (!word.trim()) return
    try {
      await addDictionaryEntry(word.trim(), pronunciation.trim() || null)
      setWord('')
      setPronunciation('')
      const updated = await getDictionary()
      setDictionary(updated)
    } catch (e) {
      console.error('Failed to add entry:', e)
      toast.error(t('dictionary.failedToAdd'))
    }
  }

  const handleRemove = async (id: number) => {
    try {
      await removeDictionaryEntry(id)
      const updated = await getDictionary()
      setDictionary(updated)
    } catch (e) {
      console.error('Failed to remove entry:', e)
      toast.error(t('dictionary.failedToRemove'))
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex gap-2">
        <input
          value={word}
          onChange={(e) => setWord(e.target.value)}
          placeholder={t('dictionary.word')}
          className="flex-1 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
        />
        <input
          value={pronunciation}
          onChange={(e) => setPronunciation(e.target.value)}
          placeholder={t('dictionary.pronunciationOptional')}
          className="flex-1 px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
        />
        <button
          onClick={handleAdd}
          disabled={!word.trim()}
          className="px-4 py-2.5 bg-accent text-white rounded-[10px] text-[13px] border-none cursor-pointer hover:bg-accent-hover disabled:opacity-40 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5"
        >
          <Plus size={14} />
          {t('dictionary.add')}
        </button>
      </div>

      <div className="border border-border rounded-[10px] overflow-hidden">
        <table className="w-full text-[13px]">
          <thead>
            <tr className="bg-bg-secondary">
              <th className="text-left px-3 py-2.5 font-medium text-text-secondary text-[11px] uppercase tracking-wider">
                {t('dictionary.word')}
              </th>
              <th className="text-left px-3 py-2.5 font-medium text-text-secondary text-[11px] uppercase tracking-wider">
                {t('dictionary.pronunciation')}
              </th>
              <th className="w-12 px-3 py-2.5"></th>
            </tr>
          </thead>
          <tbody>
            {dictionary.length === 0 ? (
              <tr>
                <td colSpan={3} className="px-3 py-8 text-center text-text-tertiary text-[13px]">
                  {t('dictionary.noEntries')}
                </td>
              </tr>
            ) : (
              dictionary.map((entry) => (
                <tr
                  key={entry.id}
                  className="border-t border-border hover:bg-bg-secondary/50 transition-colors"
                >
                  <td className="px-3 py-2.5">{entry.word}</td>
                  <td className="px-3 py-2.5 text-text-secondary">{entry.pronunciation || '-'}</td>
                  <td className="px-3 py-2.5">
                    <button
                      onClick={() => handleRemove(entry.id)}
                      className="p-1 rounded-[6px] hover:bg-bg-tertiary transition-colors bg-transparent border-none cursor-pointer text-text-tertiary hover:text-error"
                    >
                      <Trash2 size={14} />
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

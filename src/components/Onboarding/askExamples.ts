import type { PracticeOptions } from '../../lib/tauri'
import type { UI_LANGUAGES } from '../../lib/constants'

type TeachingLanguage = (typeof UI_LANGUAGES)[number]['value']
type TeachingText = Record<TeachingLanguage, string>

export const ASK_EXAMPLES: ReadonlyArray<
  TeachingText & {
    action: NonNullable<PracticeOptions['askAction']>
    selectedText?: TeachingText
  }
> = [
  {
    action: 'draft',
    en: 'Write a short email asking Alex to review the design by Thursday. Sign it Morgan.',
    zh: '帮我写一封简短邮件，请小林在周四之前评审设计，署名小陈。',
    ja: '木曜日までにデザインを確認してもらう短いメールをアレックスさん宛てに書いてください。署名はモーガンです。',
    ko: '알렉스에게 목요일까지 디자인을 검토해 달라는 짧은 이메일을 써 주세요. 서명은 모건으로 해 주세요.',
    fr: 'Rédige un court e-mail demandant à Alex de revoir le design avant jeudi. Signe Morgan.',
    de: 'Schreibe eine kurze E-Mail an Alex mit der Bitte, das Design bis Donnerstag zu prüfen. Unterschreibe mit Morgan.',
    es: 'Escribe un correo breve pidiendo a Alex que revise el diseño antes del jueves. Firma como Morgan.',
    pt: 'Escreva um e-mail curto pedindo a Alex que revise o design até quinta-feira. Assine como Morgan.',
    ru: 'Напиши короткое письмо Алексу с просьбой проверить дизайн до четверга. Подпиши: Морган.',
    it: 'Scrivi una breve e-mail chiedendo ad Alex di rivedere il design entro giovedì. Firma Morgan.',
  },
  {
    action: 'rewrite',
    en: 'Rewrite this announcement in a friendly, concise tone.',
    zh: '把这段通知改写得友好、简洁一些。',
    ja: 'このお知らせを親しみやすく簡潔な文章に書き直してください。',
    ko: '이 공지를 친근하고 간결한 문장으로 다시 써 주세요.',
    fr: 'Réécris cette annonce sur un ton amical et concis.',
    de: 'Formuliere diese Ankündigung freundlich und knapp um.',
    es: 'Reescribe este anuncio con un tono amable y conciso.',
    pt: 'Reescreva este aviso com um tom amigável e conciso.',
    ru: 'Перепиши это объявление кратко и дружелюбно.',
    it: 'Riscrivi questo annuncio con un tono cordiale e conciso.',
    selectedText: {
      en: 'The design review will take place on Thursday. Please read the documents before the meeting.',
      zh: '设计评审将在周四进行。请各位参会人员提前阅读材料。',
      ja: 'デザインレビューは木曜日に行います。参加者は会議の前に資料をお読みください。',
      ko: '디자인 검토 회의는 목요일에 열립니다. 참석자는 회의 전에 자료를 읽어 주세요.',
      fr: 'La revue du design aura lieu jeudi. Veuillez lire les documents avant la réunion.',
      de: 'Die Designbesprechung findet am Donnerstag statt. Bitte lesen Sie die Unterlagen vor dem Treffen.',
      es: 'La revisión del diseño tendrá lugar el jueves. Lee los documentos antes de la reunión.',
      pt: 'A revisão do design será na quinta-feira. Leia os documentos antes da reunião.',
      ru: 'Обсуждение дизайна состоится в четверг. Пожалуйста, прочитайте материалы до встречи.',
      it: 'La revisione del design si terrà giovedì. Leggete i documenti prima della riunione.',
    },
  },
  {
    action: 'translate',
    en: 'Translate the selected text into the chosen language.',
    zh: '把选中的文字翻译成我选择的语言。',
    ja: '選択した文章を指定した言語に翻訳してください。',
    ko: '선택한 텍스트를 지정한 언어로 번역해 주세요.',
    fr: 'Traduis le texte sélectionné dans la langue choisie.',
    de: 'Übersetze den ausgewählten Text in die gewählte Sprache.',
    es: 'Traduce el texto seleccionado al idioma elegido.',
    pt: 'Traduza o texto selecionado para o idioma escolhido.',
    ru: 'Переведи выделенный текст на выбранный язык.',
    it: 'Traduci il testo selezionato nella lingua scelta.',
    selectedText: {
      en: 'The design review starts at ten thirty on Thursday.',
      zh: '设计评审在周四上午十点半开始。',
      ja: 'デザインレビューは木曜日の午前十時半に始まります。',
      ko: '디자인 검토 회의는 목요일 오전 열 시 반에 시작합니다.',
      fr: 'La revue du design commence jeudi à dix heures et demie.',
      de: 'Die Designbesprechung beginnt am Donnerstag um halb elf.',
      es: 'La revisión del diseño empieza el jueves a las diez y media.',
      pt: 'A revisão do design começa na quinta-feira às dez e meia.',
      ru: 'Обсуждение дизайна начинается в четверг в половине одиннадцатого утра.',
      it: 'La revisione del design inizia giovedì alle dieci e mezza.',
    },
  },
  {
    action: 'answer',
    en: 'What is the difference between a prototype and a finished product?',
    zh: '原型和成品有什么区别？',
    ja: 'プロトタイプと完成品にはどのような違いがありますか？',
    ko: '프로토타입과 완성된 제품은 어떻게 다른가요?',
    fr: 'Quelle est la différence entre un prototype et un produit fini ?',
    de: 'Was ist der Unterschied zwischen einem Prototyp und einem fertigen Produkt?',
    es: '¿Cuál es la diferencia entre un prototipo y un producto terminado?',
    pt: 'Qual é a diferença entre um protótipo e um produto finalizado?',
    ru: 'Чем прототип отличается от готового продукта?',
    it: 'Qual è la differenza tra un prototipo e un prodotto finito?',
  },
]

export function askExampleText(index: number, language: string, selection = false): string {
  const example = ASK_EXAMPLES[index]
  if (!example) return ''
  const text = selection ? example.selectedText : example
  if (!text) return ''
  const locale = language.toLowerCase().split(/[-_]/)[0] as TeachingLanguage
  return Object.prototype.hasOwnProperty.call(text, locale) ? text[locale] : text.en
}

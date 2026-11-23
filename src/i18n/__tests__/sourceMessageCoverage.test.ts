import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import en from '../locales/en.json'

function literalMessageKeys(text: string, filename: string): string[] {
  const source = ts.createSourceFile(filename, text, ts.ScriptTarget.Latest, true)
  const keys: string[] = []
  const messageFields = new Set([
    'labelKey',
    'nameKey',
    'descriptionKey',
    'badgeKey',
    'periodKey',
    'ctaKey',
  ])
  const collectArgument = (argument: ts.Expression) => {
    if (ts.isStringLiteral(argument) || ts.isNoSubstitutionTemplateLiteral(argument)) {
      keys.push(argument.text)
    } else if (ts.isConditionalExpression(argument)) {
      collectArgument(argument.whenTrue)
      collectArgument(argument.whenFalse)
    }
  }
  const visit = (node: ts.Node) => {
    if (
      ts.isPropertyAssignment(node) &&
      (ts.isIdentifier(node.name) || ts.isStringLiteral(node.name)) &&
      messageFields.has(node.name.text)
    ) {
      collectArgument(node.initializer)
    }
    if (ts.isCallExpression(node)) {
      const direct = ts.isIdentifier(node.expression) && node.expression.text === 't'
      const namespaced =
        ts.isPropertyAccessExpression(node.expression) &&
        ts.isIdentifier(node.expression.expression) &&
        node.expression.expression.text === 'i18n' &&
        node.expression.name.text === 't'
      const argument = node.arguments[0]
      if ((direct || namespaced) && argument) {
        collectArgument(argument)
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(source)
  return keys
}

function messageExists(key: string): boolean {
  const read = (candidate: string) =>
    candidate.split('.').reduce<unknown>((value, part) => {
      if (!value || typeof value !== 'object') return undefined
      return (value as Record<string, unknown>)[part]
    }, en)
  return [key, `${key}_one`, `${key}_other`].some(
    (candidate) => typeof read(candidate) === 'string',
  )
}

describe('literal source message coverage', () => {
  it('collects known configuration message fields without treating arbitrary strings as keys', () => {
    expect(
      literalMessageKeys(
        "const choices = [{ labelKey: 'providers.stt.cloud', nameKey: 'upgrade.pro', descriptionKey: 'upgrade.planDescriptions.pro', badgeKey: 'upgrade.lifetimeBadge', periodKey: 'upgrade.month', ctaKey: `upgrade.subscribeToPro`, value: 'not.a.message', label: 'Human label', otherKey: 'not.a.message', labelKey: dynamicKey }];",
        'example.ts',
      ),
    ).toEqual([
      'providers.stt.cloud',
      'upgrade.pro',
      'upgrade.planDescriptions.pro',
      'upgrade.lifetimeBadge',
      'upgrade.month',
      'upgrade.subscribeToPro',
    ])
  })

  it('collects nested conditional branches and inert templates without guessing dynamic keys', () => {
    expect(
      literalMessageKeys(
        "t(active ? 'account.signIn' : failed ? 'account.networkError' : `common.cancel`); i18n.t(ready ? dynamicKey : 'common.save'); t(`errors.${code}`);",
        'example.ts',
      ),
    ).toEqual(['account.signIn', 'account.networkError', 'common.cancel', 'common.save'])
  })

  it('collects literal translation calls even when they provide an English default', () => {
    expect(
      literalMessageKeys(
        `t('account.networkError', 'English default'); i18n.t('account.cloudQuotaWarning'); t(dynamicKey); service.t('not.a.translation');`,
        'example.ts',
      ),
    ).toEqual(['account.networkError', 'account.cloudQuotaWarning'])
    expect(messageExists('missing.message.with.default')).toBe(false)
  })

  it('resolves literal message keys used in production components, hooks, stores and lib', () => {
    const sources = import.meta.glob<string>(
      [
        '../../components/**/*.{ts,tsx}',
        '../../hooks/**/*.{ts,tsx}',
        '../../stores/**/*.{ts,tsx}',
        '../../lib/**/*.{ts,tsx}',
        '!../../**/__tests__/**',
        '!../../**/*.test.{ts,tsx}',
      ],
      { query: '?raw', import: 'default', eager: true },
    )
    const missing: string[] = []
    let calls = 0
    for (const [filename, source] of Object.entries(sources)) {
      for (const key of literalMessageKeys(source, filename)) {
        calls += 1
        if (!messageExists(key)) missing.push(`${filename}: ${key}`)
      }
    }
    expect(calls).toBeGreaterThan(0)
    expect(missing).toEqual([])
  })
})

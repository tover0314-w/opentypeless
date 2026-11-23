import { describe, expect, it } from 'vitest'
import ts from 'typescript'
import desktopEntry from '../../main.tsx?raw'
import previewEntry from '../../onboarding-preview.tsx?raw'

describe('root motion preference policy', () => {
  it.each([
    { name: 'desktop', source: desktopEntry },
    { name: 'preview', source: previewEntry },
  ])('respects the system motion preference in $name', ({ source }) => {
    const syntax = ts.createSourceFile(
      'entry.tsx',
      source,
      ts.ScriptTarget.Latest,
      true,
      ts.ScriptKind.TSX,
    )
    const policies: ts.JsxElement[] = []
    const visit = (node: ts.Node) => {
      if (ts.isJsxElement(node) && node.openingElement.tagName.getText(syntax) === 'MotionConfig') {
        policies.push(node)
      }
      ts.forEachChild(node, visit)
    }
    visit(syntax)
    expect(policies).toHaveLength(1)
    const preference = policies[0].openingElement.attributes.properties.find(
      (attribute) =>
        ts.isJsxAttribute(attribute) && attribute.name.getText(syntax) === 'reducedMotion',
    )
    expect(
      preference && ts.isJsxAttribute(preference) && preference.initializer?.getText(syntax),
    ).toBe('"user"')
    expect(
      policies[0].children.some(
        (child) => ts.isJsxElement(child) || ts.isJsxSelfClosingElement(child),
      ),
    ).toBe(true)
  })
})

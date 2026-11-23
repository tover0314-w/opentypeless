import { readFileSync, realpathSync } from 'node:fs'
import { resolve, relative, isAbsolute } from 'node:path'

function block(source, pattern) {
  const match = pattern.exec(source)
  if (!match) return ''
  const opening = source.indexOf('{', match.index)
  let depth = 1
  for (let position = opening + 1; position < source.length; position += 1) {
    if (source[position] === '{') depth += 1
    if (source[position] === '}' && --depth === 0) {
      return source.slice(opening + 1, position)
    }
  }
  return ''
}

function verify(directory, htmlName) {
  const root = realpathSync(resolve(directory))
  const html = readFileSync(resolve(root, htmlName), 'utf8')
  const links = [...html.matchAll(/<link\b[^>]*>/gi)]
    .map(([tag]) => ({
      relation: /\brel\s*=\s*["']([^"']+)["']/i.exec(tag)?.[1],
      href: /\bhref\s*=\s*["']([^"']+)["']/i.exec(tag)?.[1],
    }))
    .filter((link) => link.relation === 'stylesheet' && link.href)
  if (!links.length) throw new Error('No referenced stylesheet to verify')
  const stylesheets = links.map(({ href }) => {
    if (/^(?:[a-z]+:|\/\/)/i.test(href)) throw new Error('External stylesheet cannot be verified')
    const path = realpathSync(resolve(root, href.replace(/^\//, '').split(/[?#]/)[0]))
    const relativePath = relative(root, path)
    if (relativePath.startsWith('..') || isAbsolute(relativePath)) {
      throw new Error('Stylesheet must remain inside the build directory')
    }
    return readFileSync(path, 'utf8')
  })
  const valid = stylesheets.some((css) => {
    const support = block(css, /@supports\s+not\s*\(\s*container-type\s*:\s*inline-size\s*\)/i)
    const media = block(support, /@media\s*\(\s*min-width\s*:\s*988px\s*\)/i)
    const panel = block(media, /\.onboarding-split-panel\s*\{/)
    const surface = block(media, /\.onboarding-split-surface\s*\{/)
    return (
      /(?:^|;)\s*width\s*:\s*55%\s*(?:;|$)/.test(panel) &&
      /(?:^|;)\s*display\s*:\s*flex\s*(?:;|$)/.test(surface)
    )
  })
  if (!valid) throw new Error('Referenced CSS is missing the compatible onboarding split fallback')
  console.log(`Onboarding CSS fallback verified: ${htmlName}, ${stylesheets.length} stylesheet(s)`)
}

try {
  const directory = process.argv[2]
  const htmlName = process.argv[3] ?? 'index.html'
  if (!directory)
    throw new Error('Usage: node scripts/verify-onboarding-css.mjs <build-directory> [html]')
  verify(directory, htmlName)
} catch (error) {
  console.error(error instanceof Error ? error.message : 'Onboarding CSS verification failed')
  process.exitCode = 1
}

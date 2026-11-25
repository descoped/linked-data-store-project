import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import GithubSlugger from 'github-slugger'
import { createHighlighter, type Highlighter } from 'shiki'

/** One entry of a page's contents: a `##` or `###` heading */
export interface TocEntry {
  id: string
  text: string
  level: 2 | 3
}

const decode = (text: string): string =>
  text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')

const escape = (text: string): string =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

/** The languages of the code blocks in the documentation, and the name each is labelled with */
const languages: Record<string, string> = {
  sh: 'Shell',
  console: 'Console',
  json: 'JSON',
  http: 'HTTP',
  graphql: 'GraphQL',
  properties: 'Properties',
  cypher: 'Cypher',
  java: 'Java',
  yaml: 'YAML',
  sql: 'SQL',
  xml: 'XML',
  svg: 'SVG',
}

// Shiki's name of a language, where it differs
const grammarOf: Record<string, string> = { console: 'shellsession', svg: 'xml' }

// One highlighter for the whole build: loading the grammars and the themes is the slow part
let highlighter: Promise<Highlighter> | undefined
function getHighlighter(): Promise<Highlighter> {
  highlighter ??= createHighlighter({
    themes: ['github-light', 'github-dark'],
    langs: [...new Set(Object.keys(languages).map((lang) => grammarOf[lang] ?? lang))],
  })
  return highlighter
}

/**
 * A code block coloured as GitHub colours it, in both themes: each token carries its light and its dark colour as CSS
 * variables (`--shiki-light`, `--shiki-dark`), and the stylesheet picks one by the page's theme. A language that is
 * not known, and a block without one, is shown as plain text. The block is labelled with its language, and the page
 * adds a copy button to it. A block of HTTP is an answer, which scrolls when it is long; an answer, a console session and a
 * block of text wrap their lines. A block with a comment `<!-- label: ... -->` right before it (`<!-- label: The whole stack -->`) is labelled with
 * that text instead, saying what the block is for rather than what language it is in.
 */
async function highlight(lang: string, escaped: string, title?: string): Promise<string> {
  const code = decode(escaped).replace(/\n$/, '')
  const label = languages[lang]
  const pre = label
    ? (await getHighlighter()).codeToHtml(code, {
        lang: grammarOf[lang] ?? lang,
        themes: { light: 'github-light', dark: 'github-dark' },
        defaultColor: false,
      })
    : `<pre class="shiki"><code>${escape(code)}</code></pre>`
  // An answer can be long: the page shows it in an area of its own height that scrolls. An answer, a session of
  // commands and their answers, and a block of text (a message, a log) wrap their long lines; code keeps its lines
  // and scrolls sideways.
  const kind = lang === 'http' ? ' code-answer code-wrap' : !label || lang === 'console' ? ' code-wrap' : ''
  return `<div class="code-block${kind}"><div class="code-head"><span class="code-lang">${title ? escape(decode(title)) : (label ?? 'Text')}</span></div>${pre}</div>`
}

/** Replaces each match of a pattern by what an async function makes of it */
async function replaceAsync(
  text: string,
  pattern: RegExp,
  replace: (...groups: string[]) => Promise<string>,
): Promise<string> {
  const parts: Promise<string>[] = []
  let last = 0
  for (const match of text.matchAll(pattern)) {
    parts.push(Promise.resolve(text.slice(last, match.index)), replace(...match))
    last = (match.index ?? 0) + match[0].length
  }
  parts.push(Promise.resolve(text.slice(last)))
  return (await Promise.all(parts)).join('')
}

// The folders of docs/ that hold the figures of the pages, as SVG files
const figureFolders = ['developer/images', 'user/images', 'images'].map((folder) =>
  join(process.cwd(), '..', 'docs', folder),
)

/** The SVG file of a figure by its name, from the first folder of docs/ that has it */
async function figureSvg(name: string): Promise<string | undefined> {
  for (const folder of figureFolders) {
    try {
      return await readFile(join(folder, name), 'utf8')
    } catch {
      // not in this folder
    }
  }
  return undefined
}

/**
 * A figure of docs/ (an image whose file is an SVG of docs/) put into the page as it is, so the page's stylesheet
 * colours it in the theme the reader picked: its own `<style>` is left out (on GitHub, where it is shown as an image,
 * it follows the system's theme), and the classes it draws with are styled under `.diagram`. Its own title and
 * description name it to a screen reader.
 */
async function inlineFigure(name: string): Promise<string | undefined> {
  if (!/^[\w-]+\.svg$/.test(name)) return undefined
  const svg = await figureSvg(name)
  if (!svg) return undefined
  const drawing = svg.replace(/<style>[\s\S]*?<\/style>\s*/, '').trim()
  return `<figure class="diagram">${drawing}</figure>`
}

/**
 * The HTML of a page as krantho renders its markdown, made ready to show:
 *
 * - every heading gets the id GitHub gives it (github-slugger, the algorithm GitHub uses), so a link such as
 *   `concepts.md#what-a-field-holds` leads to the same place on the site as on GitHub;
 * - a ```mermaid block becomes a `<pre class="mermaid">` with its source, which the page draws in the browser;
 * - every other code block is coloured by its language when the site is built (`highlight`);
 * - an SVG figure of docs/ is put into the page itself (`inlineFigure`);
 * - the `##` and `###` headings are listed as the page's contents.
 */
export async function prepareHtml(source: string): Promise<{ html: string; toc: TocEntry[]; hasFigures: boolean }> {
  const slugger = new GithubSlugger()
  const toc: TocEntry[] = []
  let html = source.replace(/<h([1-6])>([\s\S]*?)<\/h\1>/g, (_, level: string, inner: string) => {
    const text = decode(inner.replace(/<[^>]+>/g, ''))
    const id = slugger.slug(text)
    if (level === '2' || level === '3') toc.push({ id, text, level: Number(level) as 2 | 3 })
    return `<h${level} id="${id}"><a class="anchor" href="#${id}" aria-hidden="true" tabindex="-1">#</a>${inner}</h${level}>`
  })
  let hasFigures = false
  html = html.replace(/<pre><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g, (_, code: string) => {
    hasFigures = true
    return `<pre class="mermaid">${code}</pre>`
  })
  html = await highlightCode(html)
  html = await replaceAsync(
    html,
    /<p>\s*<img src="[^"]*\/([\w-]+\.svg)" alt="[^"]*"\s*\/?>\s*<\/p>/g,
    async (whole = '', name = '') => (await inlineFigure(name)) ?? whole,
  )
  return { html, toc, hasFigures }
}

/** The code blocks of a piece of HTML, coloured by `highlight`; for the documentation pages and the landing page */
export async function highlightCode(html: string): Promise<string> {
  return replaceAsync(
    html,
    /(?:<!--\s*label:\s*([^<>]*?)\s*-->\s*)?<pre><code(?: class="language-([\w-]+)")?>([\s\S]*?)<\/code><\/pre>/g,
    (_, title = '', lang = '', code = '') => highlight(lang, code, title || undefined),
  )
}

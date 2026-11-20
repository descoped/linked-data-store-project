import GithubSlugger from 'github-slugger'

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

/**
 * The HTML of a page as krantho renders its markdown, made ready to show:
 *
 * - every heading gets the id GitHub gives it (github-slugger, the algorithm GitHub uses), so a link such as
 *   `concepts.md#what-a-field-holds` leads to the same place on the site as on GitHub;
 * - a ```mermaid block becomes a `<pre class="mermaid">` with its source, which the page draws in the browser;
 * - the `##` and `###` headings are listed as the page's contents.
 */
export function prepareHtml(source: string): { html: string; toc: TocEntry[]; hasFigures: boolean } {
  const slugger = new GithubSlugger()
  const toc: TocEntry[] = []
  let html = source.replace(/<h([1-6])>([\s\S]*?)<\/h\1>/g, (_, level: string, inner: string) => {
    const text = decode(inner.replace(/<[^>]+>/g, ''))
    const id = slugger.slug(text)
    if (level === '2' || level === '3') toc.push({ id, text, level: Number(level) as 2 | 3 })
    return `<h${level} id="${id}"><a class="anchor" href="#${id}" aria-hidden="true" tabindex="-1">#</a>${inner}</h${level}>`
  })
  let hasFigures = false
  html = html.replace(
    /<pre><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g,
    (_, code: string) => {
      hasFigures = true
      return `<pre class="mermaid">${code}</pre>`
    },
  )
  return { html, toc, hasFigures }
}

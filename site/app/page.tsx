import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { getCMSContent, resolveSeoContent, SeoJsonLd } from '@krantho/ui-react/server'
import { collections } from '../krantho.collections'
import { CodeCopy } from '../components/code-copy'
import { CompositionFigure, SagaFigure, TimeMachineFigure } from '../components/figures'
import { SectionHash } from '../components/section-hash'
import { highlightCode } from '../lib/html'
import { site } from '../lib/krantho'

export async function generateMetadata(): Promise<Metadata> {
  const seo = await resolveSeoContent('landing', 'home')
  return { title: { absolute: seo.title }, description: seo.description, alternates: { canonical: '/' } }
}

const idOf = (name: string): string => name.toLowerCase().replace(/[^a-z0-9]+/g, '-')

/** A section's HTML cut in two: its text, and its code blocks (as `highlightCode` makes them) */
function codeOf(html: string): { text: string; code: string } {
  const blocks = html.match(/<div class="code-block[^"]*">[\s\S]*?<\/pre><\/div>/g) ?? []
  let text = html
  for (const block of blocks) text = text.replace(block, '')
  return { text, code: blocks.join('') }
}

/** The figure a section shows beside its text */
const figureOf: Record<string, () => React.ReactElement> = {
  'What a document is made of': () => <CompositionFigure />,
  'A write is a saga': () => <SagaFigure />,
}

/** Sections shown side by side in one row: the first names the second */
const pairOf: Record<string, string> = {
  'The problem': 'The time machine',
  'A model, read at start': 'Storage you choose',
  'When it fits': 'When it does not fit yet',
}

/** A section's heading and its text */
function Part({ name, html, children }: { name: string; html: string; children?: React.ReactNode }): React.ReactElement {
  return (
    <div className="row-part">
      <h2 id={idOf(name)}>{name}</h2>
      {html.trim() && <div className="prose" dangerouslySetInnerHTML={{ __html: html }} />}
      {children}
    </div>
  )
}

/**
 * The landing page: the hero from the fields of landing/home, then the sections of the section contract
 * (`collections.landing.sections`), in that order, each a row of the same two columns: a section's heading and text on
 * the left, and on the right its figure, its code, or the second section of a pair; a section with neither has its
 * heading on the left and its text on the right. A section the document lacks is left out. krantho has given a link
 * to a page of the site (`/docs/...`) the path the site is served under.
 */
export default async function Home(): Promise<React.ReactElement> {
  const doc = await site.loader.getPublished({ collection: 'landing', slug: 'home' })
  if (!doc) notFound()
  const f = doc.fields as Record<string, string>
  const { sections: raw } = await getCMSContent('landing', 'home')
  // The code blocks coloured as on the documentation pages
  const sections = Object.fromEntries(
    await Promise.all(Object.entries(raw).map(async ([name, html]) => [name, await highlightCode(html)] as const)),
  )
  // A second section of a pair is shown in the row of the first, when the document has the first
  const shownWithFirst = new Set(
    Object.entries(pairOf)
      .filter(([first]) => sections[first])
      .map(([, second]) => second),
  )
  const row = (name: string): React.ReactElement => {
    const html = sections[name] ?? ''
    const second = pairOf[name]
    if (second && sections[second]) {
      return (
        <section key={name} className="row">
          <Part name={name} html={html} />
          <Part name={second} html={sections[second] ?? ''} />
        </section>
      )
    }
    const { text, code } = codeOf(html)
    const figure = figureOf[name]
    if (!figure && !code) {
      return (
        <section key={name} className="row">
          <Part name={name} html="" />
          <div className="prose row-side" dangerouslySetInnerHTML={{ __html: text }} />
        </section>
      )
    }
    return (
      <section key={name} className="row">
        <Part name={name} html={text}>
          {name === 'See it' && (
            <p className="actions">
              <Link className="button primary" href={f['primary-href']}>
                {f['primary-label']}
              </Link>
            </p>
          )}
        </Part>
        <div className="row-side">
          {figure ? figure() : <div className="prose" dangerouslySetInnerHTML={{ __html: code }} />}
        </div>
      </section>
    )
  }
  return (
    <main className="landing">
      <SeoJsonLd collection="landing" slug="home" />
      <section className="row hero">
        <div className="row-part">
          <p className="eyebrow">{f.eyebrow}</p>
          <h1>{f.headline}</h1>
          <p className="subline">{f.subline}</p>
          <p className="actions">
            <Link className="button primary" href={f['primary-href']}>
              {f['primary-label']}
            </Link>
            <Link className="button" href={f['secondary-href']}>
              {f['secondary-label']}
            </Link>
          </p>
        </div>
        <div className="row-side">
          <TimeMachineFigure />
        </div>
      </section>
      {(collections.landing.sections ?? [])
        .filter((name) => sections[name] && !shownWithFirst.has(name))
        .map(row)}
      <CodeCopy />
      <SectionHash selector="main h2[id]" />
    </main>
  )
}

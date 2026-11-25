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

/** A section's HTML cut at its `###` headings: the text before the first, and a card for each */
function cardsOf(html: string): { intro: string; cards: { title: string; html: string }[] } {
  const parts = html.split(/(?=<h3[\s>])/)
  // A section that opens with a card has no text before it
  const intro = parts[0]?.startsWith('<h3') ? '' : (parts.shift() ?? '')
  const cards = parts.map((part) => {
    const [, title = '', body = ''] = /^<h3[^>]*>([\s\S]*?)<\/h3>([\s\S]*)$/.exec(part) ?? []
    return { title, html: body }
  })
  return { intro, cards }
}

const idOf = (name: string): string => name.toLowerCase().replace(/[^a-z0-9]+/g, '-')

/** A section's HTML cut in two: its text, and its code blocks (as `highlightCode` makes them) */
function codeOf(html: string): { text: string; code: string } {
  const blocks = html.match(/<div class="code-block[^"]*">[\s\S]*?<\/pre><\/div>/g) ?? []
  let text = html
  for (const block of blocks) text = text.replace(block, '')
  return { text, code: blocks.join('') }
}

/** A section's HTML cut at its second paragraph: what it says first, and the rest */
function leadOf(html: string): { lead: string; rest: string } {
  const at = html.indexOf('<p', html.indexOf('</p>'))
  return at < 0 ? { lead: html, rest: '' } : { lead: html.slice(0, at), rest: html.slice(at) }
}

/**
 * How a section is laid out, by its name: `split` puts its text beside its figure or its code, `fit` puts its first
 * paragraph beside the rest in a card, `quiet` is small print. A section with no layout is its text, then its cards.
 */
const layoutOf: Record<string, 'split' | 'fit' | 'quiet'> = {
  'What a document is made of': 'split',
  'When it fits': 'fit',
  Status: 'quiet',
}

/** Sections shown as two cards in one band: the first names the second */
const pairOf: Record<string, string> = { 'The problem': 'The time machine', 'See it': 'With Claude' }

/** Pairs whose cards are one above the other, each as wide as the page: their code needs the width */
const stacked = new Set(['See it'])

/** The figure a section of the contract shows after its text; its words are the section's */
const figureOf: Record<string, () => React.ReactElement> = {
  'What a document is made of': () => (
    <figure className="figure">
      <CompositionFigure />
      <figcaption>
        Person/ola holds an embedded address and a link to a company. A read at each instant gives ola as it was
        then, and follows the link to the company as it was at the same instant.
      </figcaption>
    </figure>
  ),
  'How it works': () => (
    <div className="band-split figure-split">
      <div className="prose lead">
        <p>
          A write goes through its steps one after the other, and the saga log records each one that is done. A write
          cut off by a failure or a stop is taken up again from there: by the next write, after a restart (with the
          saga log in PostgreSQL), or by another LDS on the same saga log.
        </p>
      </div>
      <figure className="figure">
        <SagaFigure />
      </figure>
    </div>
  ),
}

/**
 * The landing page: the hero from the fields of landing/home, then each section of the section contract
 * (`collections.landing.sections`) as a band, in that order; a section the document lacks is left out. krantho has
 * given a link to a page of the site (`/docs/...`) the path the site is served under.
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
  // A second section of a pair is shown in the band of the first, when the document has the first
  const shownWithFirst = new Set(
    Object.entries(pairOf)
      .filter(([first]) => sections[first])
      .map(([, second]) => second),
  )
  return (
    <main className="landing">
      <SeoJsonLd collection="landing" slug="home" />
      <section className="hero">
        <div>
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
        <figure className="figure">
          <TimeMachineFigure />
          <figcaption>
            Company/fjord has three versions. A read at each instant gets the version valid then, or nothing before
            the first.
          </figcaption>
        </figure>
      </section>
      {(collections.landing.sections ?? [])
        .filter((name) => sections[name])
        .filter((name) => !shownWithFirst.has(name))
        .map((name) => {
          const id = idOf(name)
          const second = pairOf[name]
          if (second && sections[second]) {
            return (
              <section
                key={name}
                id={id}
                className={`band band-pair${stacked.has(name) ? ' band-stack' : ''}`}
                aria-label={`${name}, ${second}`}
              >
                {[name, second].map((part) => (
                  <div key={part} id={idOf(part)} className="pair-part">
                    <h2>{part}</h2>
                    <div className="prose" dangerouslySetInnerHTML={{ __html: sections[part] ?? '' }} />
                    {part === 'See it' && (
                      <p className="actions">
                        <Link className="button primary" href={f['primary-href']}>
                          {f['primary-label']}
                        </Link>
                      </p>
                    )}
                  </div>
                ))}
              </section>
            )
          }
          const layout = layoutOf[name]
          const html = sections[name] ?? ''
          const title = <h2 id={`${id}-title`}>{name}</h2>
          if (layout === 'split') {
            const { text, code } = codeOf(html)
            const figure = figureOf[name]?.()
            return (
              <section key={name} id={id} className="band band-split" aria-labelledby={`${id}-title`}>
                <div className="split-text">
                  {title}
                  <div className="prose" dangerouslySetInnerHTML={{ __html: text }} />
                </div>
                <div className="split-side">
                  {figure}
                  {code && <div className="prose" dangerouslySetInnerHTML={{ __html: code }} />}
                </div>
              </section>
            )
          }
          if (layout === 'fit') {
            const { lead, rest } = leadOf(html)
            return (
              <section key={name} id={id} className="band band-split band-fit" aria-labelledby={`${id}-title`}>
                <div className="split-text">
                  {title}
                  <div className="prose lead" dangerouslySetInnerHTML={{ __html: lead }} />
                </div>
                <div className="split-side card">
                  <div className="prose" dangerouslySetInnerHTML={{ __html: rest }} />
                </div>
              </section>
            )
          }
          const { intro, cards } = cardsOf(html)
          return (
            <section key={name} id={id} className={`band${layout === 'quiet' ? ' band-quiet' : ''}`} aria-labelledby={`${id}-title`}>
              {title}
              {intro.trim() && <div className="prose" dangerouslySetInnerHTML={{ __html: intro }} />}
              {cards.length > 0 && (
                <div className="cards">
                  {cards.map((card) => (
                    <div key={card.title} className="card">
                      <h3 dangerouslySetInnerHTML={{ __html: card.title }} />
                      <div className="prose" dangerouslySetInnerHTML={{ __html: card.html }} />
                    </div>
                  ))}
                </div>
              )}
              {figureOf[name]?.()}
            </section>
          )
        })}
      <CodeCopy />
      <SectionHash selector="main section[id]" />
    </main>
  )
}

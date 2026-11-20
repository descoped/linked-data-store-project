import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { getCMSContent, resolveSeoContent, SeoJsonLd } from '@krantho/ui-react/server'
import { collections } from '../krantho.collections'
import { CodeCopy } from '../components/code-copy'
import { CompositionFigure, TimeMachineFigure } from '../components/figures'
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
        .map((name) => {
          const { intro, cards } = cardsOf(sections[name] ?? '')
          const id = idOf(name)
          return (
            <section key={name} id={id} className="band" aria-labelledby={`${id}-title`}>
              <h2 id={`${id}-title`}>{name}</h2>
              {intro.trim() && <div className="prose" dangerouslySetInnerHTML={{ __html: intro }} />}
              {figureOf[name]?.()}
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
            </section>
          )
        })}
      <CodeCopy />
      <SectionHash selector="main section[id]" />
    </main>
  )
}

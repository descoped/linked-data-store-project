import type { Metadata } from 'next'
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { getCMSContent, resolveSeoContent, SeoJsonLd } from '@krantho/ui-react/server'
import { collections } from '../krantho.collections'
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

/**
 * The landing page: the hero from the fields of landing/home, then each section of the section contract
 * (`collections.landing.sections`) as a band, in that order; a section the document lacks is left out. krantho has
 * given a link to a page of the site (`/docs/...`) the path the site is served under.
 */
export default async function Home(): Promise<React.ReactElement> {
  const doc = await site.loader.getPublished({ collection: 'landing', slug: 'home' })
  if (!doc) notFound()
  const f = doc.fields as Record<string, string>
  const { sections } = await getCMSContent('landing', 'home')
  return (
    <main className="landing">
      <SeoJsonLd collection="landing" slug="home" />
      <section className="hero">
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
    </main>
  )
}

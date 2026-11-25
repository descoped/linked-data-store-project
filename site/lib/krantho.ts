import { createStaticSite } from '@krantho/static'
import { initServerLoader } from '@krantho/ui-react/server'
import config from '../krantho.config'
import { collections } from '../krantho.collections'

// Pages read the markdown in ../docs through the site's loader. A link to another file of docs/
// (concepts.md#what-a-field-holds) becomes that page's address, under the path the site is served under, so the files
// read the same on GitHub and on the site. An image with copies in public/_krantho/images/ is shown as a <picture>,
// and `sizes` is how wide the page shows it: the width of the text (46rem) once the window has room for the sidebar
// beside it, else the window's width.
export const site = createStaticSite({
  config,
  collections,
  images: { sizes: '(min-width: 65rem) 46rem, 100vw' },
})
initServerLoader(site.loader, site.rendering)

/** The collections that hold documentation pages, in the order the sidebar shows them */
export const docCollections = ['docs', 'user', 'developer'] as const
export type DocCollection = (typeof docCollections)[number]

/** The sidebar's heading of each collection */
const groupOf: Record<DocCollection, string> = {
  docs: 'Start here',
  user: 'Using LDS',
  developer: 'Developing LDS',
}

/** A docs page as the sidebar and the pager list it */
export interface DocLink {
  collection: DocCollection
  slug: string
  title: string
  href: string
  group: string
  /** The sidebar's subgroup the page is indented under, from its front matter; none for a page on its own */
  section?: string
}

/** The address of a docs page: docs/README.md is the documentation's home page */
export function docHref(collection: DocCollection, slug: string): string {
  if (collection === 'docs') return slug === 'index' ? '/docs/' : `/docs/${slug}/`
  return `/docs/${collection}/${slug}/`
}

const orderOf = (fields: Record<string, unknown>): number =>
  typeof fields.order === 'number' ? fields.order : Number.MAX_SAFE_INTEGER

/** The published docs pages in reading order: each collection in turn, its pages by `order`, then title */
export async function docLinks(): Promise<DocLink[]> {
  const links: DocLink[] = []
  for (const collection of docCollections) {
    const docs = await site.loader.listPublishedSummaries(collection)
    const sorted = [...docs].sort(
      (a, b) =>
        orderOf(a.fields) - orderOf(b.fields) || String(a.fields.title).localeCompare(String(b.fields.title)),
    )
    for (const doc of sorted) {
      links.push({
        collection,
        slug: doc.slug,
        title: String(doc.fields.title),
        href: docHref(collection, doc.slug),
        group: groupOf[collection],
        ...(typeof doc.fields.section === 'string' ? { section: doc.fields.section } : {}),
      })
    }
  }
  return links
}

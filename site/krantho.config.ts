import { defineKranthoConfig } from '@krantho/config'

/**
 * The site of Linked Data Store: a landing page and the documentation. The documentation pages are the markdown files
 * in ../docs, the same files GitHub shows in the repository. The Pages workflow sets KRANTHO_SITE_BASE_URL to the
 * address Pages serves the site at; its path is the path every page is served under.
 */
export default defineKranthoConfig({
  site: {
    domain: 'localhost',
    name: 'Linked Data Store',
    description:
      'A server that keeps versioned, linked JSON documents, checks them against a model, and reads them as they were at any instant, over REST and GraphQL.',
    language: 'en',
    baseUrl: 'http://localhost:3000',
  },
  // The folder whose git history dates each page
  paths: { contentDir: '../docs' },
  // The markdown files are the content: no database, every page written at build
  persistence: { provider: 'filesystem' },
  cache: { pages: 'none' },
  // The docs hold names such as @link and @domain: no @field substitution
  content: { atNotation: false },
  // The structured data each page carries
  seo: {
    identity: 'SoftwareApplication',
    // What the site is about, with only what the repository says; on every page's graph
    site: {
      entities: [
        {
          '@type': 'SoftwareApplication',
          properties: {
            name: 'Linked Data Store',
            description:
              'A server that keeps versioned, linked JSON documents, checks them against a model, and reads them as they were at any instant, over REST and GraphQL.',
            applicationCategory: 'DeveloperApplication',
            softwareVersion: '3.0.1',
            license: 'https://www.apache.org/licenses/LICENSE-2.0',
            author: { '@type': 'Organization', name: 'Descoped', url: 'https://github.com/descoped' },
          },
        },
      ],
    },
    collections: { docs: ['TechArticle'], user: ['TechArticle'], developer: ['TechArticle'] },
  },
})

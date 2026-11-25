// Made when the site is built: a static file
export const dynamic = 'force-static'

/** The release whose compose file the site serves; it moves with the release the site describes */
const RELEASE = '3.0.1'

/** The compose file of the whole local stack in the repository, as GitHub serves it at the release's tag */
const SOURCE = `https://raw.githubusercontent.com/descoped/linked-data-store-project/${RELEASE}/plugins/lds/skills/start/assets/compose-stack.yaml`

/**
 * The compose file of the whole local stack (LDS with Neo4j, PostgreSQL and Solr), at /compose.yaml, so that the
 * landing page can start it with one short line: `curl -fsSL https://lds.descoped.io/compose.yaml | docker compose -f
 * - up -d --wait`. GitHub Pages cannot redirect over HTTP (and curl does not follow a redirect in HTML), so the site
 * serves the file itself: it is fetched from GitHub when the site is built, and is the file of the tag as it is.
 */
export async function GET(): Promise<Response> {
  const answer = await fetch(SOURCE)
  if (!answer.ok) throw new Error(`${SOURCE} answered ${answer.status}`)
  return new Response(await answer.text(), {
    headers: { 'Content-Type': 'application/yaml; charset=utf-8' },
  })
}

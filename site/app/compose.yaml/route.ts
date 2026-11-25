import { readFile } from 'node:fs/promises'
import { join } from 'node:path'

// Made when the site is built: a static file
export const dynamic = 'force-static'

/**
 * The compose file of the whole local stack (LDS with Neo4j, PostgreSQL and Solr), at /compose.yaml, so that the
 * landing page can start it with one line: `curl -fsSL https://lds.descoped.io/compose.yaml | docker compose -f - up
 * -d --wait`. It is the file of the plugin's start skill, read when the site is built, so there is one copy.
 */
export async function GET(): Promise<Response> {
  const file = join(process.cwd(), '..', 'plugins', 'lds', 'skills', 'start', 'assets', 'compose-stack.yaml')
  return new Response(await readFile(file, 'utf8'), {
    headers: { 'Content-Type': 'application/yaml; charset=utf-8' },
  })
}

import { NavLink } from '../../components/nav-link'
import { type DocLink, docLinks } from '../../lib/krantho'

/** The links of one group, in order, cut into runs of the same section: a page without one stands on its own */
function sections(links: DocLink[]): { section?: string; links: DocLink[] }[] {
  const runs: { section?: string; links: DocLink[] }[] = []
  for (const link of links) {
    const last = runs.at(-1)
    if (last && link.section && last.section === link.section) last.links.push(link)
    else runs.push({ ...(link.section ? { section: link.section } : {}), links: [link] })
  }
  return runs
}

/**
 * The documentation: the sidebar of every page, grouped by the folder of docs/ it is in, and within a group by the
 * section a page's front matter names, its pages indented under it; beside it, the page.
 */
export default async function DocsLayout({ children }: { children: React.ReactNode }): Promise<React.ReactElement> {
  const links = await docLinks()
  const groups = [...new Set(links.map((link) => link.group))]
  return (
    <div className="site-body">
      <nav id="site-nav" className="sidebar" aria-label="Documentation">
        {groups.map((group) => (
          <section key={group}>
            <h2>{group}</h2>
            <ul>
              {sections(links.filter((link) => link.group === group)).map((run) =>
                run.section ? (
                  <li key={run.section} className="sidebar-section">
                    <span className="sidebar-section-title">{run.section}</span>
                    <ul>
                      {run.links.map((link) => (
                        <li key={link.href}>
                          <NavLink href={link.href}>{link.title}</NavLink>
                        </li>
                      ))}
                    </ul>
                  </li>
                ) : (
                  run.links.map((link) => (
                    <li key={link.href}>
                      <NavLink href={link.href}>{link.title}</NavLink>
                    </li>
                  ))
                ),
              )}
            </ul>
          </section>
        ))}
      </nav>
      <main>{children}</main>
    </div>
  )
}

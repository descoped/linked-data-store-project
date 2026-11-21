# The site of Linked Data Store

The site that GitHub Pages serves at <https://lds.descoped.io/>: a landing page, and the documentation in `../docs`
as pages. It is a static site made with [krantho](https://krantho.com) and Next.js; nothing of it runs on a server.

```sh
cd site
pnpm install
pnpm dev       # http://localhost:3000, read again when a file changes
pnpm build     # the whole site, as static files in out/
```

The build reads the Markdown files as they are in the working tree. `.github/workflows/pages.yml` builds the site and
publishes it when `docs/`, `site/` or the workflow changes on `master`.

## Where the pages come from

| Address | From |
|---|---|
| `/` | `content/landing/home.md`: the hero is its front matter, and each `##` section of the body is a band of the page, in the order the collection's section contract gives; the `###` headings of a section are its cards. The two figures (`components/figures.tsx`) are SVG moved by CSS alone: a read that stops at four instants of the timeline, from the example of the concepts page. With reduced motion they show one still frame |
| `/docs/` | `../docs/README.md` |
| `/docs/<name>/` | `../docs/<name>.md` (the glossary) |
| `/docs/user/<name>/` | `../docs/user/<name>.md` |
| `/docs/developer/<name>/` | `../docs/developer/<name>.md` |

Each folder of `docs/` is a collection of its own (`krantho.collections.ts`), because a collection reads one folder.
Every page in `docs/` starts with front matter: `title` (the name in the sidebar and the browser tab), `description`
(the page's description for search engines, `llms.txt` and the sitemap) and `order` (its place in the sidebar's
group). The `# Title` of the file is the heading of the page.

## What the site adds to the Markdown

- A link to another file of `docs/` (`../glossary.md#valid-time`) becomes that page's address, under the path the
  site is served under. krantho does this.
- Every heading gets the id GitHub gives it (`lib/html.ts`, with `github-slugger`), so a link to a section leads to
  the same place on the site as on GitHub. The `##` and `###` headings are the page's contents, beside it.
- A ` ```mermaid ` block is drawn in the browser by Mermaid, which only a page with a figure loads
  (`components/mermaid.tsx`), in the theme the page is in.
- Every other code block is coloured by its language when the site is built, with Shiki in GitHub's light and dark
  themes (`lib/html.ts`), so the page sends no script for it. A bar above the block names the language (a block
  without one is "Text"), and holds a button that copies the code (`components/code-copy.tsx`).
- As the reader scrolls, the address follows the heading in view, so it can be bookmarked or shared as it stands, and
  "On this page" marks that heading (`components/section-hash.tsx`, from krantho's own site). The address is
  replaced, never added, so Back leaves the page.

## Visits

Page views are counted with [Plausible](https://plausible.io), which sets no cookies (`components/analytics.tsx`).
The tracker is part of the site's own script, and it starts only at `lds.descoped.io`, so a local build is not
counted. It sends each view to `https://e.descoped.io/e`, a relay on Descoped's own domain that forwards it to
Plausible, so blockers that refuse third-party requests or know Plausible's address let it through.

## Look

The tokens of colour, type and space are in `app/globals.css`, once for light and once for dark. The theme follows
the system until the reader picks light or dark with the button in the header; the choice is kept in the browser
and set before the page is painted. The mark in the header and the tab icon (`app/icon.svg`) is Descoped's, `|>o|`,
drawn as shapes in `components/brand.tsx`.

---
name: lds-site
description: >-
  The standard and the way of working for the LDS documentation site (site/, published at lds.descoped.io) and above
  all its landing page: tell the product's story from what the code does, in plain, forward-leaning English, on a
  grid of 40/60, 50/50 and thirds rows, with figures a newcomer understands, measured at real widths before anyone
  sees it, one section at a time. Use whenever a task touches site/ (content/landing/home.md, app/page.tsx,
  app/globals.css, components/figures.tsx, the sidebar or the look of the documentation pages), whenever someone asks
  to improve, redesign, reword or review the landing page or the site, or to add or change a figure on it.
---

# The LDS site

The site is a krantho static site (Next.js) in `site/`: the landing page is `content/landing/home.md` (its fields are
the hero, its `##` sections the rows, in the order of the section contract in `krantho.collections.ts`), rendered by
`app/page.tsx` and styled in `app/globals.css`; the documentation pages are `docs/`. Copy lives in content, layout in
code. `site/README.md` says how to run and build it.

The plan and the state of each section of the landing page are kept outside the repository, in the owner's dossier
for this repository (`site-plan-*.md`). Read it first: it says which section is being worked on and what was agreed.

## Load the guides before designing or writing

This skill is the LDS-specific layer. The craft underneath it lives in two other skills, and work done without them
has gone wrong before (a page redesigned from memory, copy that read as generated). Before changing the look or the
words of the site, load:

- `krantho:site-design`, and read its `references/landing-page.md` (the order of a landing page, microcopy, visual
  rhythm) and `references/quality.md` (accessibility, performance, motion);
- `artifact-design`, for its fundamentals and above all "Writing the copy" and "Avoid AI-generated design".

Where they and this skill differ, this skill wins: it holds the owner's decisions.

## Work one section at a time

A change made on its own and left there is how the page went wrong before. So:

1. **Draft**: write the text of the section (and what its figure shows) into the plan, not into the site.
2. **Agree**: the owner changes or approves it. Design choices (a new section, a figure, a layout) are the owner's.
3. **Build**: put the agreed section into the site, then run every check below.
4. **Sign off**: the owner looks at it on localhost; only then the plan marks it done and the next one starts.

Commit locally while working; push only when the owner asks for CI.

## The product first, then the story

The landing page sells a story, and the story is made of what the code on disk does, not of sentences picked from the
documentation. Before writing a section:

- find the feature in the code (core's `NamespaceController`, `SagaRepository`, the GraphQL builder, the providers,
  `Readiness`, ...), and say what it does for the user in one plain line;
- read the documentation for depth and for the facts a user meets (limits, `Not finished:` paragraphs), never to copy
  its sentences;
- check every claim against the code or by running LDS; every command shown is run against the released image, and
  the answer shown is the one it gave.

The plan's product inventory lists the features with their files; keep it up to date when the code changes.

## The voice

A narrator who explains, telling and forward-leaning: why first, then how, then what the reader gets. Marketing that
sells, made only of what LDS does.

- Plain, professional English: subject, verb, object. Headings are sentences in sentence case that say something.
  Never an inverted or clipped phrase: not "Storage you choose" but "Keep your documents where you want them"; not
  "A model, read at start" but "One model gives you both APIs".
- The reader's words first. An LDS word (saga, valid time, snapshot, namespace) appears only when the story needs it,
  is explained in the same breath the first time ("a saga: a write broken into steps, each recorded, so that ...")
  and links to the glossary.
- Short: one idea a sentence, about 25 words at most; three sentences a paragraph on the landing page.
- A telling sentence, not a list of facts: "LDS writes in steps and notes each one, so a crash halfway is finished
  when LDS starts again" rather than "Each step is recorded in a saga log."
- Name things by what the reader recognises, not by how LDS is built: "a log of every change", "the search index",
  before (if ever) "the transaction log", "the index queue". A part of the code earns a place on the landing page
  only when the reader needs it to understand what they get.
- No mannered devices, which read as generated: a colon that reveals the point ("The history is the data: no audit
  tables"), "not X, but Y" framing, asides set off by dashes, stock phrases. Say it in a plain sentence.
- No filler, no adjectives without a fact behind them, no claim of speed or scale without a measurement, nothing
  promised for later.

The documentation (`docs/`) keeps its own established style, set in the project's `CLAUDE.md`; the voice above is
for the landing page unless the owner extends it.

## The layout

Every section is a row of one of three kinds, each balanced on its own centre line (`app/globals.css`, "The landing
page"):

| Row | Use | Columns from 80rem |
|---|---|---|
| `row-wide` (40/60) | text beside a figure or code | 2fr 3fr |
| `row-halves` (50/50) | two sections of a pair | 1fr 1fr |
| `row-thirds` | a heading and text, then three options | three equal, from 56rem |

- One kind of row per section, never a one-off layout. No cards, no tinted grounds, no centred column that leaves
  the left edge.
- One background; space parts the rows. Colour only for the primary button, links and what a figure highlights.
  A border, fill or shadow sets off the one element that needs it, never every block.
- Running text about 65 to 75 characters a line (`max-width: 38rem` at 16 px); code and figures take the whole
  column. Headings use `text-wrap: balance`.
- Numbering and other structure only where it is true of the content: the steps of a write are a sequence, a list
  of store options is not.
- The hero and its actions stay as the owner set them: Get started (the filled button) and Read the concepts, at the
  top; the page has no closing call to action.

## Figures

- A figure explains one idea, and a reader who knows no LDS word understands it without the text beside it.
- Its labels are plain words, the same words as the text beside it. Its motion shows the one change it is about,
  and it shows one still frame under reduced motion.
- Before showing it, ask: what would a newcomer say this shows? If that is not the idea, redraw it.
- The living figures are SVG in `components/figures.tsx`, moved by CSS alone (`globals.css`, "Figures").

## Checks before the owner sees anything

1. **Measure** in the browser with `references/measure.js` (paste it into the page with the browser's JavaScript
   tool) at window widths 1657, 1280, 820 and a phone: `over` is 0 (nothing wider than the window), every row's
   columns start on its grid lines, every `pres` entry is `fits` on desktop widths, and side-by-side parts end level.
   The longest code line × the width of a character of the code font (14 px mono, about 8.4 px) must be at most the
   inner width of its block.
2. **Look** at it in light and dark (`document.documentElement.dataset.theme = 'light'`), at desktop and phone width.
3. **Read** it as a newcomer: every heading and its first sentence make sense on their own; no unexplained LDS word.
4. **Build**: `cd site && pnpm build` passes. After editing `docs/` or `content/`, restart `pnpm dev` (it caches the
   content) before looking.

Report what was measured, with the numbers, and anything that does not hold yet.

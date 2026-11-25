---
name: lds-site
description: >-
  The standard and the way of working for the LDS documentation site (site/, published at lds.descoped.io) and above
  all its landing page: tell the product's story from what the code does, in plain, forward-leaning English, on a
  grid of 40/60, 50/50 and thirds rows, with figures a newcomer understands, measured at real widths before anyone
  sees it, one section at a time; and the documentation pages it shows (docs/user, "Using LDS", and docs/developer,
  "Developing LDS"): every statement checked against the code, one home for each topic, complete and plain for its
  reader. Use whenever a task touches site/ or docs/ (content/landing/home.md, app/page.tsx,
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

The code is the truth. A sentence of the documentation, a memory or an earlier draft is a lead to check, never a source:
before a claim is shown to the owner, its place in the code is read and, where it can be, LDS is run to show it.
Show the owner the claim with its file and line and what the run answered. A consequence ("so a write may be lost")
is a claim too, and is run, not reasoned.

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
- A list item on the landing page is a bold lead on a line of its own, then its words, and neither ends with a full
  stop (a full stop stays between two sentences inside an item). The sentence that introduces the list keeps its own.

The documentation (`docs/`) keeps its own established style, set in the project's `CLAUDE.md`; the voice above is
for the landing page unless the owner extends it. How the documentation pages are checked and revised is below, in
"The documentation pages".

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

## The documentation pages

The site shows `docs/` in two groups, each for one reader: "Using LDS" (`docs/user/`: those who run LDS, give it a
model, write and read documents; no class, module or package names) and "Developing LDS" (`docs/developer/`: those
who change the code; `architecture.md` is the map). The rules of their style are in the project's `CLAUDE.md`, "The
documentation", and are followed as they are written there; this part says how a revision is done.

**The code is the only source.** A document describes the released version. When `git diff <tag> HEAD` shows no change
of the code, the code on disk is that release; otherwise read the code at the tag. Every statement is found in the
code (its file and line are noted while working, not written into the document) or shown by running the released
image; every request shown was run, with the answer that came. A statement that the code does not bear out is
corrected or removed, never softened. An earlier document, a comment in the code or a memory is a lead, not a source.

**One home for each topic.** A topic is explained once, in the document of the reader it belongs to; every other
document that needs it gives one sentence and a link to that place. Before a revision, make the map of topics: for
each, the document that is its home and the others that repeat it. The user documents explain what LDS does and how
to use it; the developer documents explain how the code does it, and link to the user document for the behaviour
rather than describing it again. The glossary defines a term once; a document links to it instead of defining it
anew.

**Complete for its reader.** A user can start, model, write, read, query, configure and operate LDS from "Using LDS"
alone; a developer can find, from `architecture.md`, the document for every module and every part of core. A
behaviour a reader meets that no document states is a gap, and is written from the code.

**Easy to read.** One idea a sentence, the reader's words first, a term linked to the glossary where it first
appears, examples run and formatted as `CLAUDE.md` says. Cut what the reader does not need: a sentence that repeats
the one before it, a list of internals in a user document, history.

**The order of work**, for the whole set or one document:

1. Map: the documents, their topics, and the code each one describes (modules, classes, configuration keys).
2. Verify: each document read against its code, statement by statement, and its requests run; a list of what is
   not true, what is missing and what repeats another document.
3. Decide the home of each repeated topic, with the map of all documents in view.
4. Revise each document: correct, fill the gaps, replace repeats by a sentence and a link, cut.
5. Check: `cd site && pnpm build` passes, links and anchors resolve, lines are at most 120 characters, the front
   matter and the rows of `docs/README.md` match; look at the changed pages on the site, light and dark.
6. Report to the owner, document by document, what was corrected, added and cut, with the code that decided it.
   Anything that is a decision about LDS, not about the text (a fault found in the code, a behaviour that looks
   unintended), is reported, not fixed in the documents.

Work on several documents at once may be given to agents, one document to each; the map and the decision of where
each topic lives are made with all of them in view, before any document is revised. An agent that runs LDS uses the
released image in memory on a port of its own, removes its container, and never builds with Maven or starts a
compose stack.

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

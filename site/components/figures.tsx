// The living figures of the landing page: SVG drawn on the server and moved by CSS alone (globals.css, "Figures").
// A read moves along the timeline and stops at a few instants; at each stop the figure shows what LDS answers then.
// The states of the stops are classes (s0 to s3 in the first figure, b0 to b2 in the second), shown one at a time.
// With reduced motion a figure shows one still frame, its second stop. The data is the examples of concepts.md:
// Version and time for the first figure, Snapshots: the time machine for the second.

/** The x of an instant on the timeline: 40 px a year, from the start of 2019 */
const x = (year: number): number => 40 + (year - 2019) * 40

/** The year axis under a timeline */
function Axis({ y, sx = x }: { y: number; sx?: (year: number) => number }): React.ReactElement {
  const years = Array.from({ length: 15 }, (_, i) => 2019 + i)
  return (
    <g className="fig-axis">
      <line x1={sx(2019)} y1={y} x2={sx(2033)} y2={y} />
      {years.map((year) => (
        <g key={year}>
          <line x1={sx(year)} y1={y} x2={sx(year)} y2={y + 4} />
          {year % 2 === 1 && (
            <text className="fig-small" x={sx(year)} y={y + 18} textAnchor="middle">
              {year}
            </text>
          )}
        </g>
      ))}
    </g>
  )
}

/** A version on a timeline: its bar, the bar lit at the stops where a read gets it, and its name */
function Version({
  from,
  to,
  y,
  name,
  at,
  open = false,
  gone = false,
  prefix = 's',
  sx = x,
}: {
  from: number
  to: number
  y: number
  name: string
  at: number[]
  open?: boolean
  gone?: boolean
  prefix?: string
  sx?: (year: number) => number
}): React.ReactElement {
  const w = sx(to) - sx(from)
  return (
    <g>
      <rect className={gone ? 'fig-bar fig-bar-gone' : 'fig-bar'} x={sx(from)} y={y} width={w} height={24} rx={4} />
      {at.map((stop) => (
        <rect key={stop} className={`fig-bar-on ${prefix}${stop}`} x={sx(from)} y={y} width={w} height={24} rx={4} />
      ))}
      {open && <path className="fig-open" d={`M${sx(to) - 10} ${y + 6}l6 6-6 6`} />}
      <text className={gone ? 'fig-gone' : undefined} x={sx(from) + w / 2} y={y + 16} textAnchor="middle">
        {name}
      </text>
    </g>
  )
}

/**
 * The time machine: Company/fjord has three versions, each valid from its own time. A read at 2019-06 finds nothing,
 * at 2021-06 Fjord Fish, at 2024-06 Fjord Seafood, at 2031-06 Fjord Group.
 */
export function TimeMachineFigure(): React.ReactElement {
  const reads = [
    { at: '2019-06-01T00:00:00Z', answer: '404 Not Found' },
    { at: '2021-06-01T00:00:00Z', answer: '{"name": "Fjord Fish"}' },
    { at: '2024-06-01T00:00:00Z', answer: '{"name": "Fjord Seafood"}' },
    { at: '2031-06-01T00:00:00Z', answer: '{"name": "Fjord Group"}' },
  ]
  return (
    <svg className="fig fig-a" viewBox="0 0 640 260" role="img" aria-labelledby="fig-a-title fig-a-desc">
      <title id="fig-a-title">The time machine</title>
      <desc id="fig-a-desc">
        The document Company/fjord has three versions: Fjord Fish from 2020, Fjord Seafood from 2023 and Fjord Group
        from 2030. A read moves along the timeline. At 2019 it finds nothing, at 2021 Fjord Fish, at 2024 Fjord
        Seafood, and at 2031 Fjord Group.
      </desc>
      <rect className="fig-card" x={20} y={10} width={600} height={78} rx={10} />
      {reads.map((read, stop) => (
        <g key={read.at} className={`s${stop}`}>
          <text className="fig-request" x={40} y={40}>
            GET /ns/Company/fjord?timestamp={read.at}
          </text>
          <text className={stop === 0 ? 'fig-missing' : 'fig-answer'} x={40} y={70}>
            {read.answer}
          </text>
        </g>
      ))}
      <text className="fig-small" x={x(2019)} y={130}>
        three versions of Company/fjord, each valid from its own date
      </text>
      <Version from={2020} to={2023} y={142} name="Fjord Fish" at={[1]} />
      <Version from={2023} to={2030} y={142} name="Fjord Seafood" at={[2]} />
      <Version from={2030} to={2033} y={142} name="Fjord Group" at={[3]} open />
      <Axis y={200} />
      <g className="fig-cursor fig-a-cursor" transform={`translate(${x(2021.42)} 0)`}>
        <line x1={0} y1={88} x2={0} y2={200} />
        <circle cx={0} cy={200} r={5} />
      </g>
    </svg>
  )
}

/** The x of an instant on the timeline of the second figure: 38 px a year from the start of 2019, after the row names */
const xb = (year: number): number => 70 + (year - 2019) * 38

/**
 * What a document is made of, on the snapshot example of concepts.md (Snapshots: the time machine): Person/ola holds a
 * value, an embedded address and a link. Its first version (2020) is in Bergen with the employer /Company/nordlys,
 * its second (2023) in Oslo with /Company/acme. Nordlys has a version from 2021 and is deleted from 2025; Acme has a
 * version from 2020. A read at an instant gives ola as it was then and follows the link to the other document as it
 * was at the same instant: at 2020-06 nordlys has no version yet, so the employer is null; at 2022-06 it is Nordlys;
 * at 2024-06 it is Acme. Three stops, the classes b0 to b2.
 */
export function CompositionFigure(): React.ReactElement {
  const stops = [
    { at: '2020-06', city: 'Bergen', link: '/Company/nordlys', target: 'Company/nordlys', answer: null },
    { at: '2022-06', city: 'Bergen', link: '/Company/nordlys', target: 'Company/nordlys', answer: 'Nordlys' },
    { at: '2024-06', city: 'Oslo', link: '/Company/acme', target: 'Company/acme', answer: 'Acme' },
  ]
  return (
    <svg className="fig fig-b" viewBox="0 0 640 372" role="img" aria-labelledby="fig-b-title fig-b-desc">
      <title id="fig-b-title">What a document is made of</title>
      <desc id="fig-b-desc">
        The document Person/ola holds a name, an embedded address that is part of it, and a field employer that links
        to another document. Its first version, from 2020, is in Bergen with the employer Company/nordlys; its second,
        from 2023, in Oslo with Company/acme. Nordlys has a version from 2021 and is deleted from 2025. A read at an
        instant follows the link to the other document as it was at the same instant: in June 2020 Nordlys has no
        version yet and the employer is null, in June 2022 it is Nordlys, in June 2024 it is Acme.
      </desc>
      <rect className="fig-card" x={20} y={10} width={290} height={150} rx={10} />
      <text className="fig-title" x={36} y={36}>
        Person/ola
      </text>
      <text x={36} y={62}>
        name: <tspan className="fig-answer">&quot;Ola&quot;</tspan>
      </text>
      <text x={36} y={88}>
        address:
      </text>
      <rect className="fig-embedded" x={110} y={72} width={184} height={26} rx={6} />
      {stops.map((stop, i) => (
        <g key={stop.at} className={`b${i}`}>
          <text x={122} y={90}>
            city: <tspan className="fig-answer">&quot;{stop.city}&quot;</tspan>
          </text>
          <text x={36} y={134}>
            employer: <tspan className="fig-link-text">&quot;{stop.link}&quot;</tspan>
          </text>
        </g>
      ))}
      <path className="fig-link" d="M300 130C350 130 350 70 396 70" />
      <path className="fig-arrow" d="M390 64l8 6-8 6" />
      <rect className="fig-card" x={400} y={32} width={220} height={76} rx={10} />
      {stops.map((stop, i) => (
        <g key={stop.at} className={`b${i}`}>
          <text className="fig-title" x={416} y={58}>
            {stop.target}
          </text>
          {stop.answer ? (
            <text x={416} y={88}>
              name: <tspan className="fig-answer">&quot;{stop.answer}&quot;</tspan>
            </text>
          ) : (
            <text className="fig-missing" x={416} y={88}>
              no version yet → null
            </text>
          )}
        </g>
      ))}
      <text className="fig-small" x={400} y={128}>
        employer links to another document,
      </text>
      <text className="fig-small" x={400} y={142}>
        read at the same moment
      </text>
      <text className="fig-small" x={36} y={178}>
        address is part of ola and versioned with it
      </text>
      <text className="fig-small" x={62} y={234} textAnchor="end">
        ola
      </text>
      <Version sx={xb} from={2020} to={2023} y={218} name="Bergen" at={[0, 1]} prefix="b" />
      <Version sx={xb} from={2023} to={2033} y={218} name="Oslo" at={[2]} prefix="b" open />
      <text className="fig-small" x={62} y={266} textAnchor="end">
        nordlys
      </text>
      <Version sx={xb} from={2021} to={2025} y={250} name="Nordlys" at={[1]} prefix="b" />
      <Version sx={xb} from={2025} to={2033} y={250} name="deleted" at={[]} prefix="b" gone open />
      <text className="fig-small" x={62} y={298} textAnchor="end">
        acme
      </text>
      <Version sx={xb} from={2020} to={2033} y={282} name="Acme" at={[2]} prefix="b" open />
      <Axis sx={xb} y={320} />
      <g className="fig-cursor fig-b-cursor" transform={`translate(${xb(2022.42)} 0)`}>
        <line x1={0} y1={206} x2={0} y2={320} />
        <circle cx={0} cy={320} r={5} />
        {stops.map((stop, i) => (
          <text key={stop.at} className={`fig-small fig-at b${i}`} x={0} y={200} textAnchor="middle">
            as of {stop.at}
          </text>
        ))}
      </g>
    </svg>
  )
}

/** A step of the saga figure: its box, the box lit at the stops where the step is done, and its name */
function SagaStep({
  x0,
  n,
  name,
  done,
}: {
  x0: number
  n: number
  name: string
  done: number[]
}): React.ReactElement {
  return (
    <g>
      <rect className="fig-bar" x={x0} y={70} width={172} height={44} rx={6} />
      {done.map((stop) => (
        <rect key={stop} className={`fig-bar-on s${stop}`} x={x0} y={70} width={172} height={44} rx={6} />
      ))}
      <text x={x0 + 86} y={97} textAnchor="middle">
        {n} · {name}
      </text>
    </g>
  )
}

/**
 * A write is a saga, on the write path of the write-path and saga documents: the steps are the transaction log, the
 * store and, with search, the index queue, and the saga log records each step that is done. LDS stops before the last
 * step; recovery reads the saga log, skips the steps that are done, and runs the rest with the input of the request
 * (a saga log that survives a restart is the one in PostgreSQL). Four stops, the classes s0 to s3.
 */
export function SagaFigure(): React.ReactElement {
  const steps = [
    { x0: 40, name: 'change log' },
    { x0: 234, name: 'document store' },
    { x0: 428, name: 'search queue' },
  ]
  const told = [
    'Step 1 is done, and the saga log notes it.',
    'Step 2 is done, and the saga log notes it.',
    'LDS stops before step 3. The write is half done.',
    'LDS runs again, reads the saga log and finishes step 3.',
  ]
  // The steps that are done at each stop, and the entries of the saga log then
  const done = [[0], [0, 1], [0, 1], [0, 1, 2]]
  return (
    <svg className="fig fig-c" viewBox="0 0 640 250" role="img" aria-labelledby="fig-c-title fig-c-desc">
      <title id="fig-c-title">A write run as a saga</title>
      <desc id="fig-c-desc">
        A write of Person/ola goes through three steps in order: the change log, the document store and the search
        queue. The saga log notes each step when it is done. LDS stops after step 2, so the write is half done. When
        LDS runs again, it reads the saga log, skips steps 1 and 2 and finishes step 3 with the data first sent.
      </desc>
      <text className="fig-request" x={40} y={40}>
        PUT /ns/Person/ola
      </text>
      {steps.map((step, i) => (
        <SagaStep
          key={step.name}
          x0={step.x0}
          n={i + 1}
          name={step.name}
          done={done.flatMap((steps, stop) => (steps.includes(i) ? [stop] : []))}
        />
      ))}
      <path className="fig-arrow" d="M214 92h14m-6 -6l6 6-6 6" />
      <path className="fig-arrow" d="M408 92h14m-6 -6l6 6-6 6" />
      <text className="fig-missing s2" x={514} y={136} textAnchor="middle">
        LDS stops
      </text>
      <text className="fig-answer s3" x={514} y={136} textAnchor="middle">
        finished on restart
      </text>
      <text className="fig-small" x={40} y={148}>
        saga log, the steps that are done
      </text>
      <rect className="fig-card" x={40} y={156} width={560} height={40} rx={8} />
      {done.map((entries, stop) => (
        <g key={stop} className={`s${stop}`}>
          {entries.map((i) => (
            <text key={i} className="fig-answer" x={(steps[i]?.x0 ?? 0) + 86} y={181} textAnchor="middle">
              step {i + 1} done
            </text>
          ))}
        </g>
      ))}
      {told.map((text, stop) => (
        <text key={text} className={`fig-told s${stop}`} x={40} y={230}>
          {text}
        </text>
      ))}
    </svg>
  )
}

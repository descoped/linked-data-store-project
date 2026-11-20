// The living figures of the landing page: SVG drawn on the server and moved by CSS alone (globals.css, "Figures").
// A read moves along the timeline and stops at four instants; at each stop the figure shows what LDS answers then.
// The four states of a stop are the classes s0 to s3, shown one at a time. With reduced motion a figure shows one
// still frame, the second stop. The data is the example of the documentation (concepts.md, Version and time).

/** The x of an instant on the timeline: 40 px a year, from the start of 2019 */
const x = (year: number): number => 40 + (year - 2019) * 40

/** The year axis under a timeline */
function Axis({ y }: { y: number }): React.ReactElement {
  const years = Array.from({ length: 15 }, (_, i) => 2019 + i)
  return (
    <g className="fig-axis">
      <line x1={x(2019)} y1={y} x2={x(2033)} y2={y} />
      {years.map((year) => (
        <g key={year}>
          <line x1={x(year)} y1={y} x2={x(year)} y2={y + 4} />
          {year % 2 === 1 && (
            <text className="fig-small" x={x(year)} y={y + 18} textAnchor="middle">
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
}: {
  from: number
  to: number
  y: number
  name: string
  at: number[]
  open?: boolean
}): React.ReactElement {
  const w = x(to) - x(from)
  return (
    <g>
      <rect className="fig-bar" x={x(from)} y={y} width={w} height={24} rx={4} />
      {at.map((stop) => (
        <rect key={stop} className={`fig-bar-on s${stop}`} x={x(from)} y={y} width={w} height={24} rx={4} />
      ))}
      {open && <path className="fig-open" d={`M${x(to) - 10} ${y + 6}l6 6-6 6`} />}
      <text x={x(from) + w / 2} y={y + 16} textAnchor="middle">
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
        the versions of Company/fjord, each valid from its own time
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

/**
 * What a document is made of: Person/ola holds a value, an embedded address and a link to Company/fjord. Both
 * documents have their own versions; a read at an instant gives ola as it was then, and follows the link to fjord as
 * it was at the same instant.
 */
export function CompositionFigure(): React.ReactElement {
  const stops = [
    { at: '2021-06', street: 'Bryggen 3', city: 'Bergen', company: 'Fjord Fish' },
    { at: '2024-06', street: 'Bryggen 3', city: 'Bergen', company: 'Fjord Seafood' },
    { at: '2027-06', street: 'Storgata 1', city: 'Oslo', company: 'Fjord Seafood' },
    { at: '2031-06', street: 'Storgata 1', city: 'Oslo', company: 'Fjord Group' },
  ]
  return (
    <svg className="fig fig-b" viewBox="0 0 640 350" role="img" aria-labelledby="fig-b-title fig-b-desc">
      <title id="fig-b-title">What a document is made of</title>
      <desc id="fig-b-desc">
        The document Person/ola holds a name, an embedded address that is part of it, and a field employer that links
        to the document Company/fjord. Both documents have versions of their own. A read at an instant gives ola as
        it was then, and follows the link to fjord as it was at the same instant: in 2021 Bergen and Fjord Fish, in
        2024 Bergen and Fjord Seafood, in 2027 Oslo and Fjord Seafood, in 2031 Oslo and Fjord Group.
      </desc>
      <rect className="fig-card" x={20} y={10} width={290} height={180} rx={10} />
      <text className="fig-title" x={36} y={36}>
        Person/ola
      </text>
      <text x={36} y={62}>
        name: <tspan className="fig-answer">&quot;Ola&quot;</tspan>
      </text>
      <text x={36} y={88}>
        address:
      </text>
      <rect className="fig-embedded" x={36} y={96} width={258} height={52} rx={6} />
      {stops.map((stop, i) => (
        <g key={stop.at} className={`s${i}`}>
          <text x={52} y={117}>
            street: <tspan className="fig-answer">&quot;{stop.street}&quot;</tspan>
          </text>
          <text x={52} y={139}>
            city: <tspan className="fig-answer">&quot;{stop.city}&quot;</tspan>
          </text>
        </g>
      ))}
      <text x={36} y={174}>
        employer: <tspan className="fig-link-text">&quot;/Company/fjord&quot;</tspan>
      </text>
      <path className="fig-link" d="M300 170C350 170 350 104 396 104" />
      <path className="fig-arrow" d="M390 98l8 6-8 6" />
      <rect className="fig-card" x={400} y={66} width={220} height={76} rx={10} />
      <text className="fig-title" x={416} y={92}>
        Company/fjord
      </text>
      {stops.map((stop, i) => (
        <text key={stop.at} className={`s${i}`} x={416} y={122}>
          name: <tspan className="fig-answer">&quot;{stop.company}&quot;</tspan>
        </text>
      ))}
      <text className="fig-small" x={400} y={162}>
        a link: another document, read
      </text>
      <text className="fig-small" x={400} y={176}>
        at the same instant
      </text>
      <text className="fig-small" x={36} y={208}>
        the address is embedded: part of ola, versioned with it
      </text>
      <text className="fig-small" x={34} y={244} textAnchor="end">
        ola
      </text>
      <Version from={2020} to={2026} y={228} name="Bergen" at={[0, 1]} />
      <Version from={2026} to={2033} y={228} name="Oslo" at={[2, 3]} open />
      <text className="fig-small" x={34} y={276} textAnchor="end">
        fjord
      </text>
      <Version from={2020} to={2023} y={260} name="Fjord Fish" at={[0]} />
      <Version from={2023} to={2030} y={260} name="Fjord Seafood" at={[1, 2]} />
      <Version from={2030} to={2033} y={260} name="Fjord Group" at={[3]} open />
      <Axis y={298} />
      <g className="fig-cursor fig-b-cursor" transform={`translate(${x(2024.42)} 0)`}>
        <line x1={0} y1={218} x2={0} y2={298} />
        <circle cx={0} cy={298} r={5} />
        {stops.map((stop, i) => (
          <text key={stop.at} className={`fig-small fig-at s${i}`} x={0} y={213} textAnchor="middle">
            as of {stop.at}
          </text>
        ))}
      </g>
    </svg>
  )
}

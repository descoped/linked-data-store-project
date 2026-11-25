// Measures the landing page as it is drawn: paste into the page with the browser's JavaScript tool, at each window
// width, and read the JSON it returns.
//   inner:  the width of the window
//   over:   how much the page is wider than the window (must be 0)
//   rows:   for each row, its kind and each part's left edge (from the content's left) and width
//   level:  for each row, the empty space below each part's last element (side by side parts end level when close)
//   pres:   for each code block, its inner width, the width its longest line needs, and whether it fits
(() => {
  const r = (el) => el.getBoundingClientRect()
  const main = r(document.querySelector('main'))
  const cv = document.createElement('canvas').getContext('2d')
  const rows = [...document.querySelectorAll('main .row')].map((s) => [
    s.className.replace('row ', ''),
    [...s.children].map((c) => `${Math.round(r(c).left - main.left)}:${Math.round(r(c).width)}`),
  ])
  const level = [...document.querySelectorAll('main .row')].map((s) =>
    [...s.children].map((c) => (c.lastElementChild ? Math.round(r(c).bottom - r(c.lastElementChild).bottom) : 0)),
  )
  const pres = [...document.querySelectorAll('main pre')].map((p) => {
    const cs = getComputedStyle(p)
    cv.font = `${cs.fontSize} ${cs.fontFamily}`
    const ch = cv.measureText('0').width
    const inner = p.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)
    const longest = Math.max(...p.textContent.split('\n').map((l) => l.length))
    return [Math.round(inner), Math.round(longest * ch), inner >= longest * ch ? 'fits' : 'WRAPS']
  })
  return JSON.stringify({
    inner: innerWidth,
    over: document.documentElement.scrollWidth - innerWidth,
    rows,
    level,
    pres,
  })
})()

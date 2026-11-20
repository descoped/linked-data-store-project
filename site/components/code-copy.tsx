'use client'

import { useEffect } from 'react'

/**
 * Gives every code block of the page a button that copies its text. Added in the browser, where copying works; without
 * JavaScript the blocks are shown without it. The button says "Copied" for two seconds, and a screen reader hears it.
 */
export function CodeCopy(): null {
  useEffect(() => {
    const added: HTMLButtonElement[] = []
    for (const block of document.querySelectorAll<HTMLElement>('.code-block')) {
      if (block.querySelector('.code-copy')) continue
      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'code-copy'
      button.textContent = 'Copy'
      button.setAttribute('aria-live', 'polite')
      button.addEventListener('click', () => {
        const text = block.querySelector('pre')?.innerText ?? ''
        void navigator.clipboard.writeText(text).then(
          () => {
            button.textContent = 'Copied'
            setTimeout(() => {
              button.textContent = 'Copy'
            }, 2000)
          },
          () => {
            button.textContent = 'Not copied'
          },
        )
      })
      ;(block.querySelector('.code-head') ?? block).append(button)
      added.push(button)
    }
    return () => {
      for (const button of added) button.remove()
    }
  }, [])
  return null
}

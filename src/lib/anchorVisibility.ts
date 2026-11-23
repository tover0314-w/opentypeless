export function isAnchorClipped(anchor: HTMLElement): boolean {
  const bounds = anchor.getBoundingClientRect()
  if (bounds.width <= 0 || bounds.height <= 0) return false
  let left = 0
  let top = 0
  let right = window.innerWidth
  let bottom = window.innerHeight
  for (let parent = anchor.parentElement; parent; parent = parent.parentElement) {
    const style = window.getComputedStyle(parent)
    const clipsHorizontal = /^(auto|scroll|hidden|clip)$/.test(style.overflowX)
    const clipsVertical = /^(auto|scroll|hidden|clip)$/.test(style.overflowY)
    if (!clipsHorizontal && !clipsVertical) continue
    const parentBounds = parent.getBoundingClientRect()
    if (parentBounds.width <= 0 || parentBounds.height <= 0) continue
    const horizontalScale = parent.offsetWidth > 0 ? parentBounds.width / parent.offsetWidth : 1
    const verticalScale = parent.offsetHeight > 0 ? parentBounds.height / parent.offsetHeight : 1
    const clientLeft =
      parent.offsetWidth > 0
        ? parentBounds.left + parent.clientLeft * horizontalScale
        : parentBounds.left
    const clientRight =
      parent.offsetWidth > 0
        ? clientLeft + parent.clientWidth * horizontalScale
        : parentBounds.right
    const clientTop =
      parent.offsetHeight > 0
        ? parentBounds.top + parent.clientTop * verticalScale
        : parentBounds.top
    const clientBottom =
      parent.offsetHeight > 0
        ? clientTop + parent.clientHeight * verticalScale
        : parentBounds.bottom
    if (clipsHorizontal) {
      left = Math.max(left, clientLeft)
      right = Math.min(right, clientRight)
    }
    if (clipsVertical) {
      top = Math.max(top, clientTop)
      bottom = Math.min(bottom, clientBottom)
    }
  }
  return (
    right <= left ||
    bottom <= top ||
    bounds.right <= left ||
    bounds.left >= right ||
    bounds.bottom <= top ||
    bounds.top >= bottom
  )
}

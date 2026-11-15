import { afterEach, describe, expect, it, vi } from 'vitest'
import { isAnchorClipped } from '../anchorVisibility'

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('popup anchor clipping', () => {
  it('treats disjoint ancestor clipping regions as invisible', () => {
    const outer = document.createElement('div')
    const inner = document.createElement('div')
    const anchor = document.createElement('button')
    outer.style.overflowY = 'hidden'
    inner.style.overflowY = 'auto'
    inner.append(anchor)
    outer.append(inner)
    document.body.append(outer)
    const bounds = {
      x: 100,
      y: 100,
      left: 100,
      right: 400,
      top: 100,
      bottom: 200,
      width: 300,
      height: 100,
      toJSON: () => ({}),
    }
    vi.spyOn(outer, 'getBoundingClientRect').mockReturnValue(bounds)
    vi.spyOn(inner, 'getBoundingClientRect').mockReturnValue({
      ...bounds,
      top: 250,
      bottom: 350,
    })
    vi.spyOn(anchor, 'getBoundingClientRect').mockReturnValue({
      ...bounds,
      top: 150,
      bottom: 300,
      height: 150,
    })
    expect(isAnchorClipped(anchor)).toBe(true)
  })

  it('uses the scroll container client area rather than its border box', () => {
    const parent = document.createElement('div')
    parent.style.overflowY = 'auto'
    const anchor = document.createElement('button')
    parent.append(anchor)
    document.body.append(parent)
    const bounds = {
      x: 100,
      y: 200,
      left: 100,
      right: 400,
      top: 200,
      bottom: 400,
      width: 300,
      height: 200,
      toJSON: () => ({}),
    }
    vi.spyOn(parent, 'getBoundingClientRect').mockReturnValue(bounds)
    Object.defineProperties(parent, {
      offsetHeight: { configurable: true, value: 200 },
      clientTop: { configurable: true, value: 10 },
      clientHeight: { configurable: true, value: 180 },
    })
    const anchorBounds = { ...bounds, top: 202, bottom: 209, height: 7 }
    vi.spyOn(anchor, 'getBoundingClientRect').mockReturnValue(anchorBounds)
    expect(isAnchorClipped(anchor)).toBe(true)
    anchorBounds.top = 211
    anchorBounds.bottom = 218
    expect(isAnchorClipped(anchor)).toBe(false)
  })

  it('detects an anchor outside a scrolling ancestor but still inside the window', () => {
    const parent = document.createElement('div')
    parent.style.overflowY = 'auto'
    const anchor = document.createElement('button')
    parent.append(anchor)
    document.body.append(parent)
    const parentBounds = {
      x: 100,
      y: 200,
      left: 100,
      right: 400,
      top: 200,
      bottom: 400,
      width: 300,
      height: 200,
      toJSON: () => ({}),
    }
    vi.spyOn(parent, 'getBoundingClientRect').mockReturnValue(parentBounds)
    const bounds = { ...parentBounds, top: 150, bottom: 190, height: 40 }
    vi.spyOn(anchor, 'getBoundingClientRect').mockReturnValue(bounds)
    expect(isAnchorClipped(anchor)).toBe(true)
    bounds.top = 210
    bounds.bottom = 250
    expect(isAnchorClipped(anchor)).toBe(false)
  })

  it('does not treat visible overflow as clipping or zero layout as evidence', () => {
    const parent = document.createElement('div')
    const anchor = document.createElement('button')
    parent.append(anchor)
    document.body.append(parent)
    expect(isAnchorClipped(anchor)).toBe(false)
  })
})

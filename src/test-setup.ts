import '@testing-library/jest-dom/vitest'

// jsdom has no layout; the styled Select scrolls its active option into view.
if (typeof Element !== 'undefined' && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}

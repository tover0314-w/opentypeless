import { cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import originalLogo from '../../../../src-tauri/icons/app-icon.svg'
import { BrandMark } from '../../MainLayout/BrandMark'
import { CapsuleLogo } from '../../Capsule/CapsuleLogo'

afterEach(cleanup)

describe('project logo consistency', () => {
  it.each([
    { name: 'sidebar', Logo: BrandMark },
    { name: 'capsule', Logo: CapsuleLogo },
  ])('uses the original bundled project icon in the $name', ({ Logo }) => {
    const { container } = render(<Logo size={28} className="project-mark" />)
    const image = container.querySelector('img')
    expect(image).toHaveAttribute('src', originalLogo)
    expect(image).toHaveAttribute('alt', '')
    expect(image).toHaveAttribute('aria-hidden', 'true')
    expect(image).toHaveAttribute('width', '28')
    expect(image).toHaveAttribute('height', '28')
    expect(image).toHaveClass('project-mark')
    expect(container.querySelector('svg')).toBeNull()
  })
})

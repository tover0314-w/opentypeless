import { fireEvent, screen, within } from '@testing-library/react'

/** Opens a styled Select and returns its listbox. */
export function openSelect(trigger: HTMLElement) {
  if (trigger.getAttribute('aria-expanded') !== 'true') fireEvent.click(trigger)
  return screen.getByRole('listbox')
}

/** Picks an option by its value from a styled Select. */
export function chooseOption(trigger: HTMLElement, value: string) {
  const listbox = openSelect(trigger)
  const option = within(listbox)
    .getAllByRole('option')
    .find((item) => item.getAttribute('data-value') === value)
  if (!option) throw new Error(`Select has no option with value "${value}"`)
  fireEvent.click(option)
}

export function selectedValue(trigger: HTMLElement) {
  return trigger.getAttribute('data-value')
}

/** Option labels and values of a styled Select, read by opening it. */
export function selectOptions(trigger: HTMLElement) {
  const listbox = openSelect(trigger)
  const options = within(listbox)
    .getAllByRole('option')
    .map((item) => ({ label: item.textContent ?? '', value: item.getAttribute('data-value') }))
  fireEvent.click(trigger)
  return options
}

import { createContext, useContext } from 'react'

export const ModalDepthContext = createContext(0)

export function useModalPopoverLayer() {
  const depth = useContext(ModalDepthContext)
  return depth > 0 ? 100 + (depth - 1) * 10 + 2 : 91
}

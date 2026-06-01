export type ToastType = 'success' | 'error' | 'info'

type ToastDispatcher = (text: string, type?: ToastType) => void
type ToastFn = ToastDispatcher & {
  success: (text: string) => void
  error: (text: string) => void
}

let addToast: ToastDispatcher = () => {}

export function setToastDispatcher(dispatcher: ToastDispatcher) {
  addToast = dispatcher
}

export const toast = ((text: string, type: ToastType = 'info') => {
  addToast(text, type)
}) as ToastFn

toast.success = (text: string) => toast(text, 'success')
toast.error = (text: string) => toast(text, 'error')

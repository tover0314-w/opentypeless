export function credentialErrorDetails(message: string): string {
  return [
    'Could not save credential in the system vault',
    'Could not delete credential from the system vault',
    'Credential save could not be verified',
    'Credential deletion could not be verified',
  ].includes(message)
    ? ''
    : message
}

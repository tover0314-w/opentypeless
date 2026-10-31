/** Typeless groups onboarding into three stages shown as a breadcrumb: sign up › set up › try it. */
export type OnboardingStage = 'signUp' | 'setUp' | 'tryIt'

export const ONBOARDING_STAGES: OnboardingStage[] = ['signUp', 'setUp', 'tryIt']

/** Welcome + account are sign-up; mode, providers and permissions are set-up; the rest is try-it. */
export function stageForStep(step: number): OnboardingStage {
  if (step <= 1) return 'signUp'
  if (step <= 5) return 'setUp'
  return 'tryIt'
}

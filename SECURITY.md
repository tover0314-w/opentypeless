# Security Policy

## Reporting a Vulnerability

Please report security vulnerabilities through [GitHub Security Advisories](https://github.com/dengxuezhao/opentypeless/security/advisories/new).

**Do not open a public issue for security vulnerabilities.**

Your report should include:

- A descriptive title
- Severity assessment (Critical / High / Medium / Low)
- Affected component(s)
- Steps to reproduce
- Impact description

We will acknowledge your report within 72 hours and aim to release a fix within 14 days for critical issues.

## Security Model

OpenTypeless follows a **Bring Your Own Key (BYOK)** model:

- Desktop API keys are stored in the operating-system credential vault; Android API keys use a non-exportable Android Keystore AES-GCM key
- No cloud account or server-side storage is required for the core product
- Audio data is sent directly from the user's machine to the chosen STT/LLM provider
- The fork contains no managed-cloud proxy, subscription, quota, or session-token path
- Android on-device and system recognition are separate routes; the system provider may use its own network service
- Android password fields cannot record, local history is opt-in and encrypted, and app backup/device transfer is disabled
- The application does not collect telemetry or usage data
- CSP is enabled in the Tauri webview

## Out of Scope

The following are not considered vulnerabilities:

- Users exposing their own API keys through misconfiguration
- Issues requiring physical access to the user's machine
- Vulnerabilities in third-party STT/LLM provider APIs

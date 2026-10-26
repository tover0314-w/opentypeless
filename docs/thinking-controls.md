# Provider-aware thinking controls

This feature builds on the final-output/SSE changes proposed in PR #151. The capability registry records its 2026-10-13 documentation baseline. Core provider guides were rechecked on 2026-10-26; the allow-list is deliberately finite and is not a claim of exhaustive model support or live endpoint acceptance.

## Behavior

- The shared registry `src/lib/thinking-capabilities.json` is consumed by both TypeScript and Rust. Each row pins provider identifiers, HTTPS hostname, existing route paths, exact model aliases/snapshots, supported choices, adapter and official sources.
- Default leaves the existing request unchanged. In particular, the GLM `thinking`, `temperature` and `top_p` overrides are preserved. Default does not mean Off, and it does not rewrite an old provider's default.
- Off is displayed only when the selected model and route have a documented full disable operation. Low and Minimal are never converted to Off. Claude Sonnet 5.5's `between_tools` mode is not offered as Off because it is not a complete disable contract.
- Changing provider, model or route retains the saved selection. If it becomes unsupported, settings show that state and the backend returns a configuration error before HTTP. This blocks the provider HTTP request, not the whole dictation: ordinary dictation retains the existing raw-transcript output fallback. The runtime capsule and localized notification identify the unsupported setting and direct users to Settings → AI Polish → Thinking strength → Default; generated-output/selection and cancellation safeguards remain unchanged. Select Default or a supported option to resume AI cleanup. Returning to the supported route makes the retained choice effective again.
- Unknown models, future snapshots, proxies, custom provider identifiers, native Gemini routes, Responses routes, HTTP origins, nonstandard ports and URLs containing credentials, queries or fragments do not gain controls. Default remains available. No prefix-matching guesses are added.
- Manual Claude budgets are 1,024 or 2,048 tokens, displayed as token budgets rather than misleading cross-provider Low/High labels. A budget must be strictly smaller than the request's existing maximum output tokens. This change never increases that maximum. Ask retains its existing 80-token output cap, so manual budgets are unavailable in Ask; the UI explicitly warns to select Default or Off before using Ask. Higher effort can consume the available output budget and still produce a blank-final error through A+B.
- The setting applies to BYOK dictation cleanup and Ask Anything, including streaming and nonstreaming provider calls. Managed Cloud has only Default. Connection benchmarks remain connectivity-only, which is stated in the UI.
- Settings, old configuration defaults, backup allow-list and backup import/export carry the same value. Old loaded configurations and backups missing the field use Default. Invalid imported enum values are not accepted. Valid but unsupported saved choices are preserved and visibly blocked.

## Capability map

The JSON registry is the authoritative, complete list of exact names. Entries in this document summarize its groups:

| Existing route | Selected model groups | Offered controls beyond Default | Serialized fields |
| --- | --- | --- | --- |
| DeepSeek Chat Completions, root or `/v1` | `deepseek-flash`, documented V4 Flash legacy aliases, `deepseek-v4-pro` | Off, Low, High, Max | `thinking.type=disabled` for Off; `thinking.type=enabled` plus `reasoning_effort` for a level |
| OpenAI `/v1/chat/completions` | GPT-5 plus its documented snapshot | Minimal, Low, Medium, High | `reasoning_effort` |
| Same | GPT-5.1 plus snapshot | Off, Low, Medium, High | Off is `reasoning_effort=none` |
| Same | GPT-5.2, GPT-5.5 plus documented snapshots | Off, Low, Medium, High, Xhigh | `reasoning_effort` |
| Same | GPT-6 Astra | Low, Medium, High, Xhigh, Max | `reasoning_effort`; no Off |
| Claude native `/v1/messages` | Listed Claude 4, 4.1 and 4.5 names | Off, budget 1,024, budget 2,048 | `thinking.type=disabled` or `enabled` with `budget_tokens` |
| Same | Claude Sonnet 4.6, Opus 4.6 | Off, Low, Medium, High, Max | `thinking.type=adaptive`, `output_config.effort`; Off is `disabled` |
| Same | Claude Fable 5.1, Opus 5.5, Sonnet 5.5 | Low, Medium, High, Xhigh, Max | Adaptive plus effort; no Off |
| Same | Claude Haiku 5.5 | Off, Low, Medium, High, Xhigh, Max | Adaptive plus effort; Off is `disabled` without an incompatible high effort override |
| Gemini `/v1beta/openai/chat/completions` | Gemini 2.5 Flash/Flash-Lite | Off, Low, Medium, High | `reasoning_effort`; Off is `none` |
| Same | Gemini 2.5 Pro | Low, Medium, High | `reasoning_effort`; no Off |
| Same | Listed Gemini 3/3.1/3.5/3.6/3.8/4 model IDs | Documented subset of Minimal, Low, Medium, High | `reasoning_effort`; no Off |

The same level names do not imply the same token budgets or latency across providers. Google's compatibility layer maps levels to its own controls. Explicit Claude thinking removes incompatible sampling temperature; explicit OpenAI choices also omit temperature. Default preserves the original payload exactly, including any pre-existing compatibility limitations for newly introduced model families.

Only the routes already implemented by OpenTypeless are used. DeepSeek's Anthropic-compatible endpoint is not added as a new protocol route. OpenRouter, Azure, custom gateways and other providers remain unavailable for explicit thinking controls until a separate verified contract is added.

## Official references

- [DeepSeek thinking controls](https://api-docs.deepseek.com/guides/thinking_mode/) and [model names/legacy aliases](https://api-docs.deepseek.com/quick_start/pricing/)
- [OpenAI reasoning guide](https://developers.openai.com/api/docs/guides/reasoning), [Chat Completions parameter reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), model-specific [GPT-5](https://developers.openai.com/api/docs/models/gpt-5), [GPT-5.1](https://developers.openai.com/api/docs/models/gpt-5.1), [GPT-5.2](https://developers.openai.com/api/docs/models/gpt-5.2), [GPT-5.5](https://developers.openai.com/api/docs/models/gpt-5.5), [GPT-6 Astra](https://developers.openai.com/api/docs/models/gpt-6-astra)
- [Claude manual thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking), [model-specific accepted/rejected thinking types](https://platform.claude.com/docs/en/build-with-claude/thinking-troubleshooting), [effort levels](https://platform.claude.com/docs/en/build-with-claude/effort), [current model IDs](https://platform.claude.com/docs/en/models/overview)
- [Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai) and [per-model thinking levels](https://ai.google.dev/gemini-api/docs/thinking)

## Validation boundary

The standalone source harness is designed to compile exact production provider/protocol/output/SSE/thinking modules and real AppConfig schema/default/normalization excerpts. Neutral loopback fixtures capture the actual serialized production request. Supported-route fixtures use HTTPS with a test-only certificate trusted by that client and DNS resolved to a loopback listener; production origin checks are not weakened for tests. There are no real API credentials, paid requests or system trust changes.

AppConfig JSON file round trips are distinct from Tauri Store integration. The harness omits desktop adapters, the full pipeline, Tauri settings I/O and managed-cloud provider execution. Full application, Windows, live API, end-to-end insertion/history and a human UI experience are not established by these tests. Check the accompanying run logs for which stages actually ran; a prepared harness is not a test pass.

### Coverage granularity

The production unit test covers every allowlisted exact model and choice with stream/nonstream serialization; frontend capability tests additionally enumerate all listed provider aliases and paths. The HTTPS capture integration test covers one exact model representative from **each of 18 capability groups**, every offered choice (including Claude Haiku 5.5 Off), and both stream modes, plus unchanged GLM and unknown-provider defaults. These are table cases inside one integration test, not separate Rust test functions or real-provider validation. Exact Ask-body and pipeline error-to-capsule mapper excerpts are compiled separately; neither is claimed as the full desktop Ask/pipeline invocation.

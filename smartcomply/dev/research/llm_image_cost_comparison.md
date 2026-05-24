# LLM Vision Model — Cost & Capability Comparison

Last updated: 2026-04-25

This document compares LLM providers for the **photo-based audit assessment** use case: image in → structured JSON (OK/NOT_OK + explanation) out.

---

## OUTPUT TOKENS ARE 5-7x INPUT COST

This is the single most important cost optimization insight. Across all major providers, output tokens cost 5-7x more than input tokens. The implication:

- **Minimize output format.** Our minimal JSON format `{"j":"OK","e":"short reason","c":0.9}` uses ~20 output tokens instead of ~100 with verbose keys.
- **At 5x output cost, reducing output tokens by 80% cuts output cost by 80%.** For a model charging $15/M output, that's the difference between $0.0015 and $0.0003 per response.
- **System prompt length matters less** — input tokens are cheap. Output brevity matters more.
- **Single-character JSON keys** (`j`, `e`, `c` vs `judgement`, `explanation`, `confidence`) save ~10 tokens per response.

**Cost impact example (Claude Sonnet 4.6, 55 checkpoints):**
- Old format (~100 output tokens): 55 × 100 × $15/1M = $0.0825 output cost
- New format (~20 output tokens): 55 × 20 × $15/1M = $0.0165 output cost
- **Savings: $0.066 per audit on output alone (80% reduction)**

---

## Hosted API Providers

### Anthropic (April 2026)

| Model | API Model ID | Input Cost (per 1M tokens) | Output Cost (per 1M tokens) | Vision? | Prompt Caching? |
|-------|-------------|---------------------------|----------------------------|---------|----------------|
| Claude Haiku 4.5 | `claude-haiku-4-5-20251001` | $1.00 | $5.00 | Yes | Yes |
| Claude Sonnet 4.6 | `claude-sonnet-4-6-20260514` | $3.00 | $15.00 | Yes | Yes (90% savings on cached) |
| Claude Opus 4.7 | `claude-opus-4-7-20260416` | $5.00 | $25.00 | Yes | Yes |

Legacy (still active on APIs):
| Claude Sonnet 4 | `claude-sonnet-4-20250514` | $3.00 | $15.00 | Yes | Yes |

Auth: API Key

### OpenAI (April 2026)

| Model | API Model ID | Input Cost (per 1M tokens) | Output Cost (per 1M tokens) | Vision? | Prompt Caching? |
|-------|-------------|---------------------------|----------------------------|---------|----------------|
| GPT-5.4 Nano | `gpt-5.4-nano` | $0.20 | $1.25 | Yes | No |
| GPT-5.4 Mini | `gpt-5.4-mini` | $0.75 | $4.50 | Yes | No |
| GPT-5.4 | `gpt-5.4` | $1.25 | $10.00 | Yes | No |
| GPT-5.5 | `gpt-5.5` | $5.00 | $30.00 | Yes | No |

Legacy (still active on APIs):
| GPT-4o Mini | `gpt-4o-mini` | $0.15 | $0.60 | Yes | No |
| GPT-4o | `gpt-4o` | $2.50 | $10.00 | Yes | No |

Auth: API Key

### Google Gemini (April 2026)

| Model | API Model ID | Input Cost (per 1M tokens) | Output Cost (per 1M tokens) | Vision? | Prompt Caching? |
|-------|-------------|---------------------------|----------------------------|---------|----------------|
| Gemini 3.1 Flash Lite | `gemini-3.1-flash-lite-preview` | $0.25 | $1.50 | Yes | Yes |
| Gemini 2.5 Flash | `gemini-2.5-flash` | $0.30 | $2.50 | Yes | Yes |
| Gemini 3 Flash | `gemini-3-flash-preview` | $0.50 | $3.00 | Yes | Yes |
| Gemini 2.5 Pro | `gemini-2.5-pro` | $1.25 | $10.00 | Yes | Yes |
| Gemini 3.1 Pro | `gemini-3.1-pro-preview` | $2.00 | $12.00 | Yes | Yes |

Auth: API Key / OAuth / Service Account

### Other Providers (not in LlmModel enum)

| Provider | Model | Input Cost (per 1M tokens) | Output Cost (per 1M tokens) | Vision? | Prompt Caching? | Auth Method |
|----------|-------|---------------------------|----------------------------|---------|----------------|-------------|
| **xAI** | Grok 2 Vision | $2.00 | $10.00 | Yes | No | API Key |
| **DeepSeek** | DeepSeek-V3 | $0.27 | $1.10 | Yes | Yes | API Key |
| **Mistral** | Pixtral Large | $2.00 | $6.00 | Yes | No | API Key |

---

## Budget Models

The cheapest vision-capable models across providers, ideal for high-volume routine checkpoints:

| Model | Provider | Input (per 1M) | Output (per 1M) | Notes |
|-------|----------|----------------|-----------------|-------|
| **GPT-5.4 Nano** | OpenAI | $0.20 | $1.25 | Lowest cost current-gen OpenAI with vision |
| **Gemini 3.1 Flash Lite** | Google | $0.25 | $1.50 | Latest lightweight Gemini |
| **Gemini 2.5 Flash** | Google | $0.30 | $2.50 | Proven reliability, context caching |
| **Gemini 3 Flash** | Google | $0.50 | $3.00 | Good balance of cost and capability |
| **GPT-5.4 Mini** | OpenAI | $0.75 | $4.50 | Step up in quality from Nano |
| **Claude Haiku 4.5** | Anthropic | $1.00 | $5.00 | Cheapest current Anthropic, prompt caching available |

At $0.20/$1.25, GPT-5.4 Nano is **15x cheaper** than Claude Sonnet 4.6 on input tokens.

---

## Open Source / Self-Hostable

| Model | Params | Vision? | GPU Required | Estimated Cost/Image (self-hosted) | Notes |
|-------|--------|---------|-------------|-----------------------------------|-------|
| **Qwen2.5-VL-7B** | 7B | Yes | 1x A10G / L4 | ~$0.0001 | Best open-source vision model at this size. Excellent for structured output. |
| **Qwen2.5-VL-72B** | 72B | Yes | 4x A100 | ~$0.001 | Near GPT-4o quality. Expensive to host. |
| **Llama 4 Scout** | 17B (active) | Yes | 1x A100 | ~$0.0005 | Meta's latest. MoE architecture, efficient. |
| **InternVL2-8B** | 8B | Yes | 1x A10G | ~$0.0001 | Strong vision benchmarks, less community support. |
| **Moondream 2** | 2B | Yes | CPU capable | ~$0.00001 | Tiny, fast, but limited reasoning for subjective checks. |

---

## Cost Estimate Per Audit (Minimal Output Format)

Assumptions:
- 55 checkpoints per audit
- ~800 input tokens per image call (image ~ 750 tokens + ~50 token user prompt)
- **~20 output tokens per response** (minimal JSON: `{"j":"OK","e":"clean and ready","c":0.95}`)
- System prompt: ~100 tokens (cached after first call)

### Budget Tier ($0.20-$0.50 input)

| Provider | Model | Cost per Audit (55 images) | With Prompt Caching |
|----------|-------|--------------------------|---------------------|
| **OpenAI** | GPT-5.4 Nano | ~$0.010 | N/A |
| **Google** | Gemini 3.1 Flash Lite | ~$0.013 | ~$0.008 |
| **Google** | Gemini 2.5 Flash | ~$0.016 | ~$0.010 |
| **Google** | Gemini 3 Flash | ~$0.025 | ~$0.015 |

### Mid Tier ($0.75-$1.25 input)

| Provider | Model | Cost per Audit (55 images) | With Prompt Caching |
|----------|-------|--------------------------|---------------------|
| **OpenAI** | GPT-5.4 Mini | ~$0.038 | N/A |
| **Anthropic** | Claude Haiku 4.5 | ~$0.050 | ~$0.028 |
| **OpenAI** | GPT-5.4 | ~$0.066 | N/A |
| **Google** | Gemini 2.5 Pro | ~$0.066 | ~$0.038 |

### Premium Tier ($2.00+ input)

| Provider | Model | Cost per Audit (55 images) | With Prompt Caching |
|----------|-------|--------------------------|---------------------|
| **Google** | Gemini 3.1 Pro | ~$0.101 | ~$0.058 |
| **Anthropic** | Sonnet 4.6 | ~$0.149 | ~$0.082 |
| **OpenAI** | GPT-5.5 | ~$0.253 | N/A |
| **Anthropic** | Opus 4.7 | ~$0.247 | ~$0.136 |

### Self-Hosted

| Model | Cost per Audit (55 images) | Notes |
|-------|--------------------------|-------|
| **Qwen2.5-VL-7B** | ~$0.006 | No API cost, GPU infra only |

---

## Cost at Scale

| Audits/Month | Budget (GPT-5.4 Nano) | Mid (Haiku 4.5) | Premium (Sonnet 4.6) | Self-hosted Qwen |
|-------------|----------------------|-----------------|---------------------|-----------------|
| 100 | $1.00 | $5.00 | $14.90 | $0.60 (GPU only) |
| 1,000 | $10.00 | $50.00 | $149.00 | $0.60 (GPU only) |
| 10,000 | $100.00 | $500.00 | $1,490.00 | $0.60 (GPU only) |

---

## Recommendations

1. **Output tokens dominate cost.** The minimal JSON format (`{"j":"OK","e":"...","c":0.9}`) reduces output tokens from ~100 to ~20, cutting output cost by 80%. This is the highest-impact optimization.

2. **For routine checkpoints (damage, cleanliness), budget models suffice. Reserve premium models for subjective/nuanced assessments.** This is the core cost-optimization principle.

3. **Start with Claude Haiku 4.5 or GPT-5.4 Nano** — Test whether budget models handle your checkpoint types. For binary pass/fail (e.g., "is the fire extinguisher present?"), they are likely sufficient.

4. **Use prompt caching on Anthropic and Gemini** — The system prompt is identical across all 55 calls in an audit. Caching saves ~45% on input costs for those providers.

5. **Use a tiered approach** — Route simple checkpoints to budget models, complex/subjective assessments to premium models. The `LlmModel` enum supports this with `estimateCost()` for runtime cost tracking.

6. **Use accuracy comparison** — Run the same 20 test photos through all providers. Track agreement rate. If budget models agree with Sonnet 90%+ of the time on a checkpoint type, default to budget for that type.

7. **Self-host Qwen later** — When you're doing 1,000+ audits/month, the economics of self-hosting become compelling. Until then, hosted APIs are simpler.

---

## Image Token Estimation

Most providers charge based on image dimensions:
- **Anthropic**: Images are resized to fit within a budget. A 800x600 photo ~ 750-1,000 tokens.
- **OpenAI**: "low detail" mode = 85 tokens. "high detail" = 170 tokens per 512x512 tile. A typical photo ~ 500-1,500 tokens.
- **Google Gemini**: Images are counted as ~258 tokens regardless of size.

For cost optimization, resize audit photos to 800px width before upload. This is sufficient for visual assessment and keeps image token costs consistent.

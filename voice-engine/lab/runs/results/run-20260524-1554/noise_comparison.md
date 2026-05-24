# Noise robustness comparison — run-20260524-1554

Generated: `2026-05-24T15:58:28.654924+00:00`

White Gaussian noise mixed at target SNR. FNR = false negative rate (expected cues missed). FPR = false positive rate (false alarms). Δ = delta vs clean baseline (negative = better).

## sherpa_onnx

| Noise level | Pass | Fail | FP | Total | FNR | Δ vs clean | Δ vs noisy (recovery) | FPR |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| clean | 216 | 4 | 0 | 220 | 1.8% | — | — | 0.0% |
| SNR +15 dB (quiet room) | 207 | 13 | 0 | 220 | 5.9% | +4.1pp | — | 0.0% |
| SNR +5 dB (showroom) | 190 | 30 | 0 | 220 | 13.6% | +11.8pp | — | 0.0% |
| SNR 0 dB (very noisy) | 126 | 94 | 0 | 220 | 42.7% | +40.9pp | — | 0.0% |

### Cue-level FNR by noise level

| Cue ID | clean | snr15db | snr5db | snr0db |
|---|---:|---:|---:|---:|
| `honda.feature.bose_premium_audio_honda` | 14.3% | 14.3% | 42.9% | 85.7% |
| `honda.feature.front-wheel-drive` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.heated-side-mirrors` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.honda_sensing` | 0.0% | 0.0% | 14.3% | 28.6% |
| `honda.feature.honda_sensing_360plus` | 0.0% | 0.0% | 28.6% | 71.4% |
| `honda.feature.hondalink` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.idle-stop` | 0.0% | 0.0% | 0.0% | 33.3% |
| `honda.feature.paddle-shifters` | 0.0% | 0.0% | 33.3% | 33.3% |
| `honda.feature.real_time_awd` | 0.0% | 0.0% | 0.0% | 57.1% |
| `honda.feature.reverse` | 0.0% | 0.0% | 0.0% | 16.7% |
| `honda.feature.roof-rails` | 0.0% | 0.0% | 0.0% | 100.0% |
| `universal.feature.hands_free_power_tailgate` | 0.0% | 12.5% | 0.0% | 50.0% |
| `universal.feature.head_up_display` | 0.0% | 0.0% | 0.0% | 25.0% |
| `universal.feature.heated_front_seats` | 0.0% | 0.0% | 12.5% | 62.5% |
| `universal.feature.heated_steering_wheel` | 0.0% | 0.0% | 0.0% | 0.0% |
| `universal.feature.led_headlights` | 0.0% | 25.0% | 25.0% | 25.0% |
| `universal.feature.panoramic_moonroof` | 15.4% | 30.8% | 46.2% | 84.6% |
| `universal.feature.ventilated_front_seats` | 0.0% | 0.0% | 25.0% | 62.5% |
| `universal.feature.wireless_android_auto` | 11.1% | 33.3% | 33.3% | 88.9% |
| `universal.feature.wireless_apple_carplay` | 0.0% | 12.5% | 25.0% | 68.8% |
| `universal.feature.wireless_phone_charger` | 0.0% | 0.0% | 0.0% | 100.0% |
| `workflow.buyers_order_confirmation` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.discovery_better_mileage` | 0.0% | 0.0% | 0.0% | 40.0% |
| `workflow.discovery_budget_signal` | 0.0% | 0.0% | 0.0% | 100.0% |
| `workflow.discovery_highway_commute` | 0.0% | 0.0% | 0.0% | 33.3% |
| `workflow.discovery_school_runs` | 0.0% | 0.0% | 0.0% | 33.3% |
| `workflow.discovery_towing_cargo` | 0.0% | 0.0% | 0.0% | 50.0% |
| `workflow.exterior_focus` | 0.0% | 0.0% | 10.0% | 0.0% |
| `workflow.finance_introduction` | 0.0% | 0.0% | 33.3% | 50.0% |
| `workflow.front_line_ready` | 0.0% | 0.0% | 0.0% | 11.1% |
| `workflow.hospitality_offer` | 0.0% | 0.0% | 0.0% | 16.7% |
| `workflow.interior_focus` | 0.0% | 0.0% | 0.0% | 7.7% |
| `workflow.recommendation_logic` | 0.0% | 0.0% | 0.0% | 60.0% |
| `workflow.self_introduction` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.test_drive_opening` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.trade_in_dent` | 0.0% | 0.0% | 33.3% | 33.3% |
| `workflow.trade_in_tires` | 0.0% | 0.0% | 0.0% | 80.0% |
| `workflow.trial_close` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.walkaround_opening` | 0.0% | 0.0% | 0.0% | 0.0% |

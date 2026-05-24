# Noise robustness comparison — run-20260524-0829

Generated: `2026-05-24T08:33:52.615435+00:00`

White Gaussian noise mixed at target SNR. FNR = false negative rate (expected cues missed). FPR = false positive rate (false alarms). Δ = delta vs clean baseline (negative = better).

## apple_speech_transcriber

| Noise level | Pass | Fail | FP | Total expected | FNR | ΔFNR | FPR | ΔFPR |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| clean | 210 | 10 | 0 | 220 | 4.5% | — | 0.0% | — |
| SNR +15 dB | 210 | 10 | 0 | 220 | 4.5% | +0.0pp | 0.0% | +0.0pp |
| SNR +5 dB | 201 | 19 | 0 | 220 | 8.6% | +4.1pp | 0.0% | +0.0pp |
| SNR +0 dB | 179 | 41 | 0 | 220 | 18.6% | +14.1pp | 0.0% | +0.0pp |

### Cue-level FNR by noise level

| Cue ID | clean | snr15db | snr5db | snr0db |
|---|---:|---:|---:|---:|
| `honda.feature.bose_premium_audio_honda` | 28.6% | 28.6% | 42.9% | 28.6% |
| `honda.feature.front-wheel-drive` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.heated-side-mirrors` | 0.0% | 0.0% | 0.0% | 100.0% |
| `honda.feature.honda_sensing` | 0.0% | 0.0% | 0.0% | 14.3% |
| `honda.feature.honda_sensing_360plus` | 0.0% | 0.0% | 0.0% | 14.3% |
| `honda.feature.hondalink` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.idle-stop` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.paddle-shifters` | 0.0% | 0.0% | 0.0% | 66.7% |
| `honda.feature.real_time_awd` | 14.3% | 14.3% | 28.6% | 28.6% |
| `honda.feature.reverse` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.roof-rails` | 0.0% | 0.0% | 100.0% | 100.0% |
| `universal.feature.hands_free_power_tailgate` | 0.0% | 0.0% | 0.0% | 12.5% |
| `universal.feature.head_up_display` | 12.5% | 12.5% | 12.5% | 25.0% |
| `universal.feature.heated_front_seats` | 0.0% | 0.0% | 0.0% | 25.0% |
| `universal.feature.heated_steering_wheel` | 0.0% | 0.0% | 0.0% | 0.0% |
| `universal.feature.led_headlights` | 0.0% | 12.5% | 0.0% | 25.0% |
| `universal.feature.panoramic_moonroof` | 7.7% | 7.7% | 23.1% | 69.2% |
| `universal.feature.ventilated_front_seats` | 0.0% | 0.0% | 12.5% | 37.5% |
| `universal.feature.wireless_android_auto` | 33.3% | 33.3% | 55.6% | 77.8% |
| `universal.feature.wireless_apple_carplay` | 6.2% | 6.2% | 6.2% | 12.5% |
| `universal.feature.wireless_phone_charger` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.buyers_order_confirmation` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.discovery_better_mileage` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.discovery_budget_signal` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.discovery_highway_commute` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.discovery_school_runs` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.discovery_towing_cargo` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.exterior_focus` | 0.0% | 0.0% | 0.0% | 10.0% |
| `workflow.finance_introduction` | 0.0% | 0.0% | 0.0% | 16.7% |
| `workflow.front_line_ready` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.hospitality_offer` | 0.0% | 0.0% | 0.0% | 16.7% |
| `workflow.interior_focus` | 0.0% | 0.0% | 7.7% | 0.0% |
| `workflow.recommendation_logic` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.self_introduction` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.test_drive_opening` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.trade_in_dent` | 16.7% | 0.0% | 16.7% | 0.0% |
| `workflow.trade_in_tires` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.trial_close` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.walkaround_opening` | 0.0% | 0.0% | 0.0% | 0.0% |

## whisperkit

| Noise level | Pass | Fail | FP | Total expected | FNR | ΔFNR | FPR | ΔFPR |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| clean | 217 | 3 | 0 | 220 | 1.4% | — | 0.0% | — |
| SNR +15 dB | 214 | 6 | 0 | 220 | 2.7% | +1.4pp | 0.0% | +0.0pp |
| SNR +5 dB | 176 | 44 | 0 | 220 | 20.0% | +18.6pp | 0.0% | +0.0pp |
| SNR +0 dB | 160 | 60 | 0 | 220 | 27.3% | +25.9pp | 0.0% | +0.0pp |

### Cue-level FNR by noise level

| Cue ID | clean | snr15db | snr5db | snr0db |
|---|---:|---:|---:|---:|
| `honda.feature.bose_premium_audio_honda` | 14.3% | 14.3% | 42.9% | 57.1% |
| `honda.feature.front-wheel-drive` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.heated-side-mirrors` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.honda_sensing` | 0.0% | 0.0% | 14.3% | 0.0% |
| `honda.feature.honda_sensing_360plus` | 0.0% | 0.0% | 14.3% | 28.6% |
| `honda.feature.hondalink` | 0.0% | 0.0% | 0.0% | 100.0% |
| `honda.feature.idle-stop` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.paddle-shifters` | 0.0% | 0.0% | 0.0% | 33.3% |
| `honda.feature.real_time_awd` | 0.0% | 0.0% | 14.3% | 14.3% |
| `honda.feature.reverse` | 0.0% | 0.0% | 0.0% | 0.0% |
| `honda.feature.roof-rails` | 0.0% | 0.0% | 0.0% | 100.0% |
| `universal.feature.hands_free_power_tailgate` | 0.0% | 0.0% | 12.5% | 37.5% |
| `universal.feature.head_up_display` | 0.0% | 0.0% | 12.5% | 25.0% |
| `universal.feature.heated_front_seats` | 0.0% | 0.0% | 12.5% | 25.0% |
| `universal.feature.heated_steering_wheel` | 0.0% | 0.0% | 0.0% | 0.0% |
| `universal.feature.led_headlights` | 0.0% | 0.0% | 12.5% | 12.5% |
| `universal.feature.panoramic_moonroof` | 0.0% | 30.8% | 38.5% | 61.5% |
| `universal.feature.ventilated_front_seats` | 0.0% | 0.0% | 37.5% | 50.0% |
| `universal.feature.wireless_android_auto` | 11.1% | 11.1% | 44.4% | 66.7% |
| `universal.feature.wireless_apple_carplay` | 6.2% | 0.0% | 37.5% | 43.8% |
| `universal.feature.wireless_phone_charger` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.buyers_order_confirmation` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.discovery_better_mileage` | 0.0% | 0.0% | 20.0% | 20.0% |
| `workflow.discovery_budget_signal` | 0.0% | 0.0% | 100.0% | 0.0% |
| `workflow.discovery_highway_commute` | 0.0% | 0.0% | 16.7% | 16.7% |
| `workflow.discovery_school_runs` | 0.0% | 0.0% | 16.7% | 16.7% |
| `workflow.discovery_towing_cargo` | 0.0% | 0.0% | 25.0% | 0.0% |
| `workflow.exterior_focus` | 0.0% | 0.0% | 0.0% | 10.0% |
| `workflow.finance_introduction` | 0.0% | 0.0% | 33.3% | 33.3% |
| `workflow.front_line_ready` | 0.0% | 0.0% | 11.1% | 11.1% |
| `workflow.hospitality_offer` | 0.0% | 0.0% | 16.7% | 33.3% |
| `workflow.interior_focus` | 0.0% | 0.0% | 7.7% | 0.0% |
| `workflow.recommendation_logic` | 0.0% | 0.0% | 20.0% | 40.0% |
| `workflow.self_introduction` | 0.0% | 0.0% | 10.0% | 10.0% |
| `workflow.test_drive_opening` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.trade_in_dent` | 0.0% | 0.0% | 50.0% | 66.7% |
| `workflow.trade_in_tires` | 0.0% | 0.0% | 20.0% | 20.0% |
| `workflow.trial_close` | 0.0% | 0.0% | 0.0% | 0.0% |
| `workflow.walkaround_opening` | 0.0% | 0.0% | 0.0% | 0.0% |

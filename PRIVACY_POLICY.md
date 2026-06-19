# Privacy Policy — Misfit Cyber Analog Watch Face

**Last updated:** June 18, 2026

## Overview

Misfit Cyber Analog is a Wear OS watch face for Samsung Watch6. This policy describes what data the app accesses, how it is used, and what is never collected.

---

## Data Accessed On-Device

The watch face reads the following data directly from your device's sensors and health services. **This data never leaves your device and is never transmitted to us.**

| Data | Purpose |
|---|---|
| Step count | Progress arc toward daily step goal |
| Active minutes | Progress arc toward daily activity goal |
| Heart rate | Displayed in the BPM indicator dot |
| Calories burned | Displayed in the calorie indicator dot |
| Device location (coarse) | Used once per 30-minute cycle to fetch local weather from Open-Meteo |

---

## Weather Data

To display current conditions, the watch face sends your **approximate GPS coordinates** (latitude/longitude, rounded to 4 decimal places) to [Open-Meteo](https://open-meteo.com) — a free, open-source weather API. No account, identifier, or personal information is included in this request.

Open-Meteo's privacy policy is available at: https://open-meteo.com/en/terms

Weather is fetched every 30 minutes while the watch face is active. No weather query history is stored or logged by this app.

---

## Data We Do Not Collect

- We do not collect, store, or transmit any health or fitness data.
- We do not use analytics, crash reporting, or telemetry services.
- We do not use advertising SDKs.
- We do not create user accounts or profiles.
- We do not share any data with third parties beyond the weather API request described above.

---

## Permissions

| Permission | Reason |
|---|---|
| `BODY_SENSORS` | Read heart rate from the watch sensor |
| `BODY_SENSORS_BACKGROUND` | Continue reading heart rate while screen is off |
| `ACTIVITY_RECOGNITION` | Detect active movement for activity tracking |
| `health.READ_HEART_RATE` | Read heart rate via Wear OS Health Services |
| `health.READ_STEPS` | Read step count via Wear OS Health Services |
| `health.READ_ACTIVE_CALORIES_BURNED` | Read calorie data via Wear OS Health Services |
| `INTERNET` | Fetch weather data from Open-Meteo |
| `ACCESS_NETWORK_STATE` | Check connectivity before weather requests |
| `ACCESS_COARSE_LOCATION` | Determine location for local weather lookup |

---

## Children's Privacy

This app is not directed at children under 13 and does not knowingly collect any information from children.

---

## Changes to This Policy

If this policy changes, the updated version will be posted in this repository with a new "Last updated" date.

---

## Contact

For questions about this privacy policy, contact: tony@modularmisfits.com

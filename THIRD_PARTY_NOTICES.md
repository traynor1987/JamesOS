# Third-party components and distribution notice

James OS source is licensed under Apache-2.0; that licence applies only to original James OS material and does not change the terms of third-party libraries, Android platform components, SDKs, or trademarks.

## Dependencies

The Android and Wear modules declare standard dependencies from their respective publishers, including AndroidX, Kotlin, Google Play Services/Wearable, and KotlinX. Their source/binary licences remain their own and are resolved through the normal Gradle dependency metadata.

## Samsung Health Sensor SDK

`samsung-health-sensor-api-1.4.1.aar` is Samsung-provided third-party SDK material. Its standalone public-source redistribution status has not been established by an authoritative Samsung licence in this repository. It is therefore intentionally **not tracked** and must never be treated as Apache-2.0 material.

Developers obtain the AAR directly from Samsung under Samsung's terms and place it at `wear/libs/samsung-health-sensor-api-1.4.1.aar`, or set `JAMES_SAMSUNG_SENSOR_AAR_PATH` / `-PjamesSamsungHealthSensorAar` to an externally obtained copy. Trusted release builds receive a separately protected `SAMSUNG_HEALTH_SENSOR_AAR_BASE64` secret only at build time; it is not emitted as an artifact.

This clean public repository contains neither the standalone AAR nor the release signing material. The former private archive remains private and is never imported here.

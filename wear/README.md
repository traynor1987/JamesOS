# Samsung Health Sensor SDK setup

James OS supports Samsung Health Sensor integration in the Wear application. The Samsung Health Sensor SDK is third-party software and its standalone AAR is intentionally not distributed by this repository.

## Developer setup

Obtain the Samsung Health Sensor SDK directly from Samsung under Samsung's applicable terms. Place the exact AAR at:

```
wear/libs/samsung-health-sensor-api-1.4.1.aar
```

That path is ignored by Git. Alternatively point Gradle at a separately obtained copy:

```
JAMES_SAMSUNG_SENSOR_AAR_PATH=/absolute/path/samsung-health-sensor-api-1.4.1.aar gradle :wear:assembleDebug
# or
gradle -PjamesSamsungHealthSensorAar=/absolute/path/samsung-health-sensor-api-1.4.1.aar :wear:assembleDebug
```

If it is absent, Wear build tasks fail with a direct setup message. Public pull-request validation deliberately does not receive this proprietary dependency and validates the source, phone module, tests, lint, and public-repository security gate only.

## Trusted release builds

A trusted, main-only release uses the separate protected secret `SAMSUNG_HEALTH_SENSOR_AAR_BASE64` to reconstruct the AAR in runner temporary storage. It is verified as a ZIP/AAR, used through `JAMES_SAMSUNG_SENSOR_AAR_PATH`, never uploaded as an artifact, and removed when the job ends.

Never commit this AAR unless Samsung supplies explicit written redistribution rights for public source hosting.

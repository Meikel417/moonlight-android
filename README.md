# Moonlight DE (Hub)

Meikels eigene Version von Moonlight für Android, komplett auf Deutsch. Sie streamt den Desktop und Spiele vom PC aufs Handy und ist der Anfang der **Hub-App**: ein Startbildschirm mit Kacheln für die eigenen PCs, später auch mit Sprachsteuerung und PC-Werkzeugen.

Basis ist der Moonlight-Fork von [Axixi2233](https://github.com/Axixi2233/moonlight-android), der wiederum auf [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) aufbaut.

## Herunterladen

- **Neueste Version:** [Releases](https://github.com/Meikel417/moonlight-android/releases/latest), dort die `Moonlight-DE-….apk` herunterladen.
- Installieren per ADB: `adb install -r Moonlight-DE-….apk`
- Danach kommen Updates über den **„⬆ Update“**-Knopf im Hub (Titel „Hub“ antippen sucht von Hand).
- Die App läuft mit eigenem Paketnamen (`com.limelight.meikel`) neben dem offiziellen Moonlight.

## Was die App kann

### Hub-Startbildschirm
- Dunkles Design mit Hellblau.
- **Statuszeile:** Tailscale an/aus, Heim-WLAN (mehrere möglich), welcher PC läuft.
- **Host-Kacheln:** antippen startet direkt den Desktop-Stream, ist der PC aus, wird er geweckt. Langer Druck öffnet die App-Liste.
- **Netz-Prüfung:** Vor dem Verbinden wird geprüft, ob du daheim bist oder Tailscale läuft, sonst kannst du Tailscale direkt einschalten.
- Kacheln für **Sprache** und **PC-Werkzeuge** sind vorbereitet (kommen in Stufe 2 und 3).

### Streaming (aus dem Axixi-Fork)
- Eigene Auflösung, Bildrate und Bitrate.
- **Volle PC-Tastatur** im Stream, Ziffernblock, Tastenkombis, Kurztasten-Leiste im Hochformat.
- **Monitor wechseln** im Spielmenü (Bildschirm 1–4).
- **Zoom:** zwei Finger zoomen, ziehen verschiebt den Ausschnitt, doppelt tippen setzt zurück.
- Verschiedene Maus- und Touch-Modi (Maus, Touchpad, Multitouch), virtueller Controller mit eigenen Layouts.
- HDR, externe Monitore, Leistungsanzeige (Bildrate, Bitrate, Latenz).
- Controller über USB und Bluetooth inkl. Vibration.

## Selbst bauen

GitHub Actions baut bei jedem Push auf `master` automatisch eine APK und veröffentlicht sie als Release (`.github/workflows/build-apk.yml`).

Lokal: Android Studio mit Android SDK 34 und NDK `27.0.12077973`, dann

```shell
git submodule update --init --recursive
./gradlew -PincludeKishiHaptics=false -PincludeStereo3dAi=false :app:assembleNonRootDebug
```

Die APK landet in `app/build/outputs/apk/nonRoot/debug/`.

## Dank und Lizenz

Dank an [Moonlight Android](https://github.com/moonlight-stream/moonlight-android), [Axixi2233](https://github.com/Axixi2233/moonlight-android) und [Sunshine](https://github.com/LizardByte/Sunshine). Moonlight stammt von Cameron Gutman, Diego Waxemberg, Aaron Neyer und Andrew Hennessy.

Lizenz: [GPL-3.0](LICENSE.txt).

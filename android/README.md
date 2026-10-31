# Cue app (Android)

A native shell around Cue. The screens are the web app's (queue, Focus, Lift, Cook, Build, Mood) in a WebView, so they never drift from what Claude renders. What's native is everything the web app couldn't do well:

- **Now notification**, drawn by the app from the same payload web push sends (`lib/push.js` `nowContent`). It's pinned, shows the context in the header ("Cue · Morning routine · 2/4") and the progress ring as its large icon. Done, Start 25 min and Not now don't collapse the shade the way web notification buttons do, so the next item replaces it silently and a routine can be walked from the shade. A running focus block shows a live countdown and a progress bar.
- **Add** on the Now notification: type a capture straight into the shade (goes to `/capture`, same as the app's box).
- **Home-screen widget** (long-press the home screen, Widgets, Cue): the Now item with Done / Start and Not now, a + for capture, and what's left of today's list. It moves on whenever the Now notification does, when you leave the app, and every 30 minutes.
- **Quick capture** as a small dialog: the widget's +, long-pressing the app icon (Capture), or sharing text to Cue from any app.
- Timers (rest, focus, kitchen) get their own channel with an alarm sound; everything else is a normal reminder. Each channel can be tuned in Android's notification settings.

Pushes go through Firebase Cloud Messaging. The server sends to both web push subscriptions and app tokens (`lib/fcm.js`, `push.fcm` in the store), so the browser keeps working alongside. Every ping is a high-priority data message; the app builds the notification, so the server decides what and when exactly as before.

## Setup (once)

1. **Firebase project.** console.firebase.google.com, Add project (Analytics off). Then Add app, Android, package `com.nickcorona.cue`. Download `google-services.json` and commit it at `android/app/google-services.json`. It isn't secret: it ships inside every APK. Without it the app builds and runs, just with no push.
2. **Server key.** Firebase, Project settings, Service accounts, Generate new private key. In Vercel (project `nick-tasks`), add env `FCM_SERVICE_ACCOUNT` with the whole JSON file as the value (raw or base64), then redeploy.
3. **Signing key**, so updates install over the old app. GitHub repo `cue`, Settings, Secrets and variables, Actions: `CUE_KEYSTORE_B64` (the .jks, base64), `CUE_KEY_PASSWORD`, and `CUE_KEY_ALIAS` if the alias isn't `cue`. The scale app's key works: same values as its `SCALE_*` secrets with `CUE_KEY_ALIAS=scale`. Without them CI signs with a throwaway debug key and every update needs an uninstall.
4. **Install with Obtainium**, like Scale: Add app, `https://github.com/nickcorona/cue`. The GitHub token Obtainium uses needs read access to the `cue` repo too. Every CI build is its own release (`android-v<run>`, version `1.<run>`).
5. Open Cue, paste the app key (the part after `/app/` in your sign-in link, or the whole link), allow notifications.
6. Remove the old installed web app from the home screen (or block its notifications in Chrome), or every ping shows twice.

If a ping comes late, set Cue's battery mode to Unrestricted (long-press the icon, App info, Battery). Samsung also needs it off "Sleeping apps".

## Build locally

```
cd android && ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Needs JDK 17+ and the Android SDK (`sdk.dir` in `local.properties`, or `ANDROID_HOME`). `ScreenshotTest` renders the widget and the Now notification to `app/build/screens/*.png` so layout changes can be checked without a phone; CI keeps them as the `screens` artifact. For a release-signed build put the key at `app/cue.jks` and set `CUE_KEY_PASSWORD`.

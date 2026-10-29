# Screen Grab

Records every piece of text on a chosen part of the screen, in any app, until you stop it.
Built on the same phone-only pipeline as your APK template.

## Build it

1. Put these files in a new GitHub repo (same layout as your template repo).
2. Optional but recommended: copy `signing/debug.keystore.b64` from your template repo into this one, so future builds install over old ones.
3. **Actions** tab, **Build APK**, **Run workflow**. When it is green, download `screen-grab.apk` from **Releases**.

## First-time setup on the phone

1. Install the APK.
2. Open Screen Grab, tap **Open Accessibility settings**, then **Installed apps**, then **Screen Grab**, switch it on.
3. If the switch is greyed out (Android 13+ for apps installed from outside the Play Store): tap **App info** in the app, open the three-dot menu, tap **Allow restricted settings**, then repeat step 2.

## Using it

- A floating bar appears over every app: `⋮⋮` (drag to move), `● REC`, `▭ AREA`.
- **AREA**: drag a box over the text you want. It stays saved (green outline). **FULL SCREEN** resets it.
- **REC**: grabs what is on screen immediately, then keeps adding new text as you scroll. The button shows how many lines are saved.
- **STOP**: takes one last snapshot and saves the session inside the app as `.md` or `.txt`.
- Open a session in the app to Copy, Share, Export (to Downloads/ScreenGrab) or Delete.
- Scrolling up through old content (chat history)? Set **Scroll direction** to UP in the app before recording.

## How the smart resume works

Each snapshot is compared with the end of what is already saved. Lines that overlap are skipped, new lines are appended.
Fixed headers and repeated words ("Reply", "Like") are handled. If you fling so fast that nothing overlaps,
a `[...]` marker is written so you know something may be missing between two screens.

## Limits

- Apps that block screen reading (banking apps, secure screens) and screens drawn as pictures or game graphics give no text.
- Password fields are always skipped.
- Rotating the phone resets the area (pixel positions change).

# Driftbox: Project Blueprint

Blueprint version: 14 (Nov 17, 2026)
Repo: https://github.com/comadrez/mailapp
App id: com.comadrez.mailapp (app name "Driftbox")

## Where we are

**>>> v1.2 Driftbox: written, waiting for build check and phone test (release signing from v1.0 still needed once) <<<**

Next action: (1) push v1.2 and check the normal debug build is green, (2) do the one-time Release signing setup below, (3) run the Release workflow and install the signed APK. Tell Claude about anything that goes wrong (build log or what you saw). Optional after that: v0.9 (mail.gw).

## Milestones

- [x] **v0.1** mail.com IMAP/SMTP client. Dropped: mail.com IMAP/SMTP is Premium-only.
- [x] **v0.2** Lucide in-app icons (ISC) and an original adaptive app icon.
- [x] **v0.3** Pivot to temp mail with the free Mail.tm API (basic UI).
- [x] **v0.4** Driftbox: renamed, login-first screen with a small Register link, Register screen (username, domain picker, password, random generator), account switcher at the top right when there are several addresses, encrypted storage, swipe to delete, filter chips, menu, About screen with all limits and the Mail.tm credit.
- [x] **v0.5** Fix pass from the first real test. Skipped: v0.4 built green and passed the whole checklist on the phone (Nov 17). New bugs found later go into the next version.
- [x] **v0.6** Quality of life: tappable links (with an "Open this link?" confirmation), one-tap copy of verification codes, attachment download (save to a file you pick), pull-to-refresh (Compose BOM raised to 2024.09.00).
- [x] **v0.7** Live updates (Mercure/SSE) instead of 10 second polling, and new-mail notifications. Reworked in v1.0: notifications are on by default like a normal email app, no "checking every minute" text, silent minimal "Driftbox" background notification (Android requires one for a live connection, Mail.tm has no push service), the watcher restarts after a phone reboot.
- [x] **v0.8** Extras: new Settings screen (menu > Settings) with the Notifications switch, an address age reminder (Off / 1 / 3 / 7 / 30 days: banner on the inbox plus a notification if notifications are on) and auto-delete of old addresses (same choices, with a confirmation, runs only while the app is open).
- [ ] **v0.9 (optional)** Second provider mail.gw: same API at api.mail.gw and mercure.mail.gw, needs a provider field on every saved address.
- [x] **v1.0** Release: signed release APK built by a separate Release workflow (tag `v*` or manual run), code shrinking/obfuscation (R8), debug builds now install side by side as "Driftbox (debug)", privacy note in About and PRIVACY.md, notifications hide content on the lock screen.
- [ ] **v1.1** Reliable background running: Settings > "Keep running in background" asks Android to ignore battery optimization for Driftbox (adds REQUEST_IGNORE_BATTERY_OPTIMIZATIONS), shows whether it is on, and links to app settings for phone-brand auto-start lists. The watcher service, boot start and app-open start already existed from v1.0.
- [ ] **v1.2** Gmail-style look: green/mint theme, rounded search pill (searches the loaded messages of the open address) with menu button and address avatar, side drawer (Inbox, Unread, addresses, Add address, Settings, About, Copy address, Delete, Sign out), rounded mail cards with round sender letters and an unread dot, "New address" button. The old filter chips and top-right menu were folded into the drawer. **<- we are here**

## Release signing (one time, in Termux)

The release APK must always be signed with the same key, or Android will refuse to install it over the previous one. Make the key once and keep it safe.

1. Make the key (choose a password, use the same one everywhere below):

```
pkg install openjdk-17
keytool -genkeypair -v -keystore ~/driftbox.jks -alias driftbox -keyalg RSA -keysize 2048 -validity 10000 -storepass 'YOUR_PASSWORD' -keypass 'YOUR_PASSWORD' -dname "CN=Driftbox, O=comadrez, C=US"
```

2. **Back up `~/driftbox.jks` and the password somewhere safe (not in the repo).** If you lose them you can never update the app again, only reinstall it as a new one. `.gitignore` already blocks `*.jks` and `*.b64` from being pushed.

3. Make the text version of the key and copy it:

```
base64 -w0 ~/driftbox.jks > ~/driftbox.jks.b64
cat ~/driftbox.jks.b64
```

4. On GitHub: repo > Settings > Secrets and variables > Actions > New repository secret. Add two secrets:
   - `KEYSTORE_BASE64`: the long text from step 3
   - `KEYSTORE_PASSWORD`: your password

5. Build the release: GitHub > Actions > "Release APK" > Run workflow (or push a tag: `git tag v1.0 && git push origin v1.0`). When it is green, download the `driftbox-release` artifact. Older release artifacts are deleted automatically.

6. The first signed install: the release app has the same id as the old debug-signed app (v0.4 to v0.8) but a different signature, so **uninstall the old Driftbox first**. Saved addresses are lost with it (Android backup is off), but you can sign in again with each address and password.

Debug builds (every push) now install as a separate app called "Driftbox (debug)", so they no longer replace the release app. They have their own saved addresses.

## How to push an update (Termux)

```
cd ~/mailapp
unzip -o ~/storage/downloads/<zip name>.zip
git add -A
git commit -m "<message>"
git push
```

Then: GitHub > Actions > latest run > Artifacts > mailapp-debug.

## Tech

- Kotlin, Jetpack Compose (BOM 2024.09.00), OkHttp (+ okhttp-sse), AndroidX security-crypto.
- Built by GitHub Actions: `build.yml` makes the debug APK on every push (no keys needed); `release.yml` makes the signed, shrunk (R8) release APK on a `v*` tag or manual run, using the secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` (key alias is `driftbox`).
- Both workflows need `actions: write` permission (already set in the files) to delete old artifacts.
- App ids: release `com.comadrez.mailapp`, debug `com.comadrez.mailapp.debug`.
- Shrinking rules live in `app/proguard-rules.pro` (Tink and OkHttp rules).
- Addresses and passwords are stored on the phone only, encrypted (EncryptedSharedPreferences).
- Files: `MailTm.kt` (API client), `MainActivity.kt` (state and screens), `Extract.kt` (finds links and verification codes in message text), `Store.kt` (encrypted storage shared by the app and the watcher, also the reminder and auto-delete settings), `MailWatch.kt` (background watcher service and notifications), `res/drawable` (icons).

## Mail.tm API notes

- Base URL: https://api.mail.tm (no API key).
- Flow: GET /domains, POST /accounts, POST /token, GET /me, then GET /messages with a Bearer token.
- Also used: GET /messages/{id} (also lists attachments), GET /messages/{id}/attachment/{attachmentId} (download, needs the Bearer token), PATCH /messages/{id} (mark read), DELETE /messages/{id}, DELETE /accounts/{id}.
- No sending and no password reset in the API docs we read.
- Live updates: Mercure server-sent events at https://mercure.mail.tm/.well-known/mercure?topic=/accounts/{id} with the Bearer token. We treat any event as "something changed" and reload the message list, so we do not depend on the event payload.
- Limit: 8 requests per second per IP.
- Terms: attribution link to mail.tm must stay visible (it lives in the About screen, reachable from the main menu; keep it reachable); no reselling, no proxying, no illegal use.
- Docs: https://docs.mail.tm

## Constraints and decisions

- Phone only (Termux), no PC.
- GitHub free plan: keep builds few. After every successful build the workflow deletes all older `mailapp-debug` artifacts right away, so only the newest APK is left (added Nov 17). No expiry timer is used. If a build fails, the previous APK stays.
- Icons: Lucide (ISC), credits in ICONS.md and the About screen.
- License (Nov 17): proprietary, all rights reserved (`LICENSE` file, copyright line in About). Third-party parts keep their own licenses (Lucide ISC in ICONS.md, Apache 2.0 libraries, Mail.tm terms). A license only gives legal protection, it cannot stop someone from copying an APK. Keep the repo public or make it private (free private repos exist, but free Actions minutes are limited).
- Not using mail.com or other IMAP providers anymore.
- Name: Driftbox. Normal-email-app interface: login first, small Register link at the bottom.
- Account switcher button goes at the top right when there is more than one address.
- All limits are listed in the About screen so other users can read them.
- Mail.tm credit goes in About, not on the login or inbox screens (decided Nov 16).

## Known limits (also shown in the About screen)

- Receive only (no compose, no Sent).
- Auto-delete is permanent and only runs while the app is open (not in the background). Address age comes from Mail.tm's creation date.
- No password recovery.
- New mail arrives through a live connection to Mail.tm and notifies instantly, even with the app closed. Android requires a silent notification for that connection, and it uses some battery. Phones that kill background apps may delay notifications.
- Attachments can be saved but not previewed. Links ask for confirmation first. Verification codes are guessed from text (a number of 4-8 digits with a word like "code" or "verify" just before it), so some can be missed.
- Addresses can be removed by Mail.tm at any time; mail is not private.
- If Mail.tm objects to the credit being only in About, move the link back to the main screen.
- Privacy note: About screen and `PRIVACY.md`.

## How to use this file

When a step is done: tick its box, move the ">>>" marker to the next milestone, update "Next action", and bump the blueprint version. Send the whole file back each time, so there is always one up-to-date copy.

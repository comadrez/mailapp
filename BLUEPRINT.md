# Driftbox: Project Blueprint

Blueprint version: 10 (Nov 17, 2026)
Repo: https://github.com/comadrez/mailapp
App id: com.comadrez.mailapp (app name "Driftbox")

## Where we are

**>>> v1.0 Driftbox: written, waiting for signing setup, first release build and phone test <<<**

Next action: (1) push v1.0 and check the normal debug build is green, (2) do the one-time Release signing setup below, (3) run the Release workflow and install the signed APK, (4) run the v1.0 test checklist. Optional after that: v0.9 (mail.gw).

## Milestones

- [x] **v0.1** mail.com IMAP/SMTP client. Dropped: mail.com IMAP/SMTP is Premium-only.
- [x] **v0.2** Lucide in-app icons (ISC) and an original adaptive app icon.
- [x] **v0.3** Pivot to temp mail with the free Mail.tm API (basic UI).
- [x] **v0.4** Driftbox: renamed, login-first screen with a small Register link, Register screen (username, domain picker, password, random generator), account switcher at the top right when there are several addresses, encrypted storage, swipe to delete, filter chips, menu, About screen with all limits and the Mail.tm credit.
- [x] **v0.5** Fix pass from the first real test. Skipped: v0.4 built green and passed the whole checklist on the phone (Nov 17). New bugs found later go into the next version.
- [x] **v0.6** Quality of life: tappable links (with an "Open this link?" confirmation), one-tap copy of verification codes, attachment download (save to a file you pick), pull-to-refresh (Compose BOM raised to 2024.09.00).
- [x] **v0.7** Live updates (Mercure/SSE) instead of 10 second polling, with a check every minute as backup, and optional new-mail notifications (menu > Notifications, background service with a small ongoing notification).
- [x] **v0.8** Extras: new Settings screen (menu > Settings) with the Notifications switch, an address age reminder (Off / 1 / 3 / 7 / 30 days: banner on the inbox plus a notification if notifications are on) and auto-delete of old addresses (same choices, with a confirmation, runs only while the app is open).
- [ ] **v0.9 (optional)** Second provider mail.gw: same API at api.mail.gw and mercure.mail.gw, needs a provider field on every saved address.
- [ ] **v1.0** Release: signed release APK built by a separate Release workflow (tag `v*` or manual run), code shrinking/obfuscation (R8), debug builds now install side by side as "Driftbox (debug)", privacy note in About and PRIVACY.md, notifications hide content on the lock screen. **<- we are here**

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

## v1.0 test checklist

- [ ] Normal debug build (push) is green and installs as "Driftbox (debug)" next to the old app
- [ ] Release workflow is green (if R8 reports missing classes or the build fails, send the error log)
- [ ] Signed release APK installs after uninstalling the old app and opens as "Driftbox"
- [ ] Release app: register or sign in, receive mail, open a message, swipe delete (shrinking can break things that only show in release, so test every screen)
- [ ] Release app: Settings > Notifications works, a "New mail" notification appears with the app closed
- [ ] Lock screen set to hide sensitive content: the notification shows only "New mail" and the address
- [ ] About shows "Version 1.0", a Privacy section and the Mail.tm link
- [ ] Release app crashes? Send me what you did and any error text (release logs keep file and line numbers)

## v0.8 test checklist (done, no problems reported)

- [ ] GitHub Actions build is green
- [ ] APK installs over v0.7, addresses are still there, About says "Version 0.8"
- [ ] Menu now has "Settings" instead of "Notifications"; the Notifications switch inside Settings works as before (permission, ongoing notification on/off)
- [ ] Reminder: set it to "1 day" (an address older than a day shows a banner "This address is N days old" with Dismiss); Dismiss hides it and it does not come back for that address
- [ ] Reminder with notifications on and the app closed: one "This address is N days old" notification per address (not repeated)
- [ ] Reminder "Off" shows no banner
- [ ] Auto-delete: choosing a day count shows a confirmation that says how many of your addresses are already older; Cancel changes nothing
- [ ] Auto-delete with a brand new throwaway address only (do this test with an address you don't need): after it passes the chosen age it disappears by itself while the app is open, with a "Deleted old address" message
- [ ] Setting auto-delete back to "Off" asks nothing and stops deleting
- [ ] Old addresses from v0.4 to v0.7 get their age filled in automatically after opening the inbox (the reminder banner can then appear for them)

## v0.7 test checklist (done, no problems reported)

- [ ] GitHub Actions build is green (new library: okhttp-sse; if it fails, send the error log)
- [ ] APK installs over v0.6, addresses are still there, About says "Version 0.7"
- [ ] Open the inbox: the empty screen says "Live: new mail appears instantly" after a second or two
- [ ] With the inbox open, send an email to the address: it shows up in a few seconds without pulling or tapping refresh
- [ ] Menu > Notifications: Android asks for permission, then the menu says "Notifications: on" and a small ongoing "Driftbox is watching" notification appears
- [ ] Close the app (swipe it away from recents), send an email: a "New mail" notification appears with sender and subject
- [ ] Tapping the notification opens Driftbox
- [ ] With the app open on screen, new mail does NOT also pop a notification
- [ ] Menu > Notifications again turns it off and the ongoing notification disappears
- [ ] Second address: mail sent to it while the app is closed also notifies (shows the address in the notification)
- [ ] Sign out of every address: the ongoing notification goes away by itself
- [ ] Battery: after a day with notifications on, check Android battery usage for Driftbox and tell me what it says

## v0.6 test checklist (done, no problems reported)

- [ ] GitHub Actions build is green (if the Compose update breaks the build, send the error log)
- [ ] APK installs over v0.4, existing addresses are still there, About says "Version 0.6"
- [ ] Pull down on the inbox list refreshes it (also works on the empty "Waiting for emails" screen)
- [ ] Send yourself an email containing a link: it shows underlined in the message, tapping asks "Open this link?", Open goes to the browser, Cancel does nothing
- [ ] Send an email with text like "Your verification code is 482913": a "Copy 482913" button shows above the body, tapping it copies the code (paste it somewhere to check)
- [ ] Email without a code shows no code button (no false alarms on normal mails)
- [ ] Email with an attachment: it appears under "Attachments" with its size, Save opens the file picker, the saved file opens fine
- [ ] Swipe delete, Unread filter, switcher, sign out and delete address still work (nothing from v0.4 broke)

## v0.4 test checklist (done Nov 17, all passed)

- [x] GitHub Actions build is green
- [x] APK installs over the old app and opens as "Driftbox"
- [x] First launch shows the login screen with a small Register link at the bottom
- [x] Register: domain list loads, "Generate" fills username and password, "Create address" works
- [x] Inbox opens after registering, address shown under "Inbox"
- [x] Menu > Copy address copies it
- [x] An email sent to the address shows up within about 10 seconds
- [x] Opening it shows the text and makes it normal (not bold)
- [x] Swipe a message left or right deletes it
- [x] Unread filter works
- [x] Menu > Add address, register or sign in a second address: the switcher icon appears at the top right
- [x] Switching between addresses works
- [x] Sign out, then sign in again with address and password
- [x] Menu > Delete this address works
- [x] Menu > About and limits shows the limits, and "Powered by mail.tm" opens mail.tm

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
- Live connection while the app is open (backup check every minute). Notifications are optional and cost some battery; phones that kill background apps may delay them. After a phone restart, open Driftbox once to restart the watcher (no boot receiver yet).
- Attachments can be saved but not previewed. Links ask for confirmation first. Verification codes are guessed from text (a number of 4-8 digits with a word like "code" or "verify" just before it), so some can be missed.
- Addresses can be removed by Mail.tm at any time; mail is not private.
- If Mail.tm objects to the credit being only in About, move the link back to the main screen.
- Privacy note: About screen and `PRIVACY.md`.

## How to use this file

When a step is done: tick its box, move the ">>>" marker to the next milestone, update "Next action", and bump the blueprint version. Send the whole file back each time, so there is always one up-to-date copy.

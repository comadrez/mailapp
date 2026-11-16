# Driftbox: Project Blueprint

Blueprint version: 5 (Nov 17, 2026)
Repo: https://github.com/comadrez/mailapp
App id: com.comadrez.mailapp (app name "Driftbox")

## Where we are

**>>> v0.6 Driftbox: written, waiting for the first build and phone test <<<**

Next action: push v0.6, wait for the green check in Actions, install the APK over the old one, and run the v0.6 test checklist below. If it all works, the next milestone is v0.7.

## Milestones

- [x] **v0.1** mail.com IMAP/SMTP client. Dropped: mail.com IMAP/SMTP is Premium-only.
- [x] **v0.2** Lucide in-app icons (ISC) and an original adaptive app icon.
- [x] **v0.3** Pivot to temp mail with the free Mail.tm API (basic UI).
- [x] **v0.4** Driftbox: renamed, login-first screen with a small Register link, Register screen (username, domain picker, password, random generator), account switcher at the top right when there are several addresses, encrypted storage, swipe to delete, filter chips, menu, About screen with all limits and the Mail.tm credit.
- [x] **v0.5** Fix pass from the first real test. Skipped: v0.4 built green and passed the whole checklist on the phone (Nov 17). New bugs found later go into the next version.
- [ ] **v0.6** Quality of life: tappable links (with an "Open this link?" confirmation), one-tap copy of verification codes, attachment download (save to a file you pick), pull-to-refresh (Compose BOM raised to 2024.09.00). **<- we are here**
- [ ] **v0.7** Live updates (SSE) instead of 10 second polling, and new-mail notifications.
- [ ] **v0.8** Extras: address expiry reminder, auto-delete of old addresses, maybe a second provider (mail.gw) as an option.
- [ ] **v1.0** Release: release signing, code shrinking/obfuscation (R8) to make copying harder, final polish, short privacy note.

## v0.6 test checklist

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

- Kotlin, Jetpack Compose (BOM 2024.09.00), OkHttp, AndroidX security-crypto.
- Built by GitHub Actions (debug APK, no signing keys).
- Addresses and passwords are stored on the phone only, encrypted (EncryptedSharedPreferences).
- Files: `MailTm.kt` (API client), `MainActivity.kt` (state and screens), `Extract.kt` (finds links and verification codes in message text), `res/drawable` (icons).

## Mail.tm API notes

- Base URL: https://api.mail.tm (no API key).
- Flow: GET /domains, POST /accounts, POST /token, GET /me, then GET /messages with a Bearer token.
- Also used: GET /messages/{id} (also lists attachments), GET /messages/{id}/attachment/{attachmentId} (download, needs the Bearer token), PATCH /messages/{id} (mark read), DELETE /messages/{id}, DELETE /accounts/{id}.
- No sending and no password reset in the API docs we read.
- Limit: 8 requests per second per IP.
- Terms: attribution link to mail.tm must stay visible (it lives in the About screen, reachable from the main menu; keep it reachable); no reselling, no proxying, no illegal use.
- Docs: https://docs.mail.tm

## Constraints and decisions

- Phone only (Termux), no PC.
- GitHub free plan: keep builds few; artifact retention can be shortened to 3 days.
- Icons: Lucide (ISC), credits in ICONS.md and the About screen.
- License (Nov 17): proprietary, all rights reserved (`LICENSE` file, copyright line in About). Third-party parts keep their own licenses (Lucide ISC in ICONS.md, Apache 2.0 libraries, Mail.tm terms). A license only gives legal protection, it cannot stop someone from copying an APK. Keep the repo public or make it private (free private repos exist, but free Actions minutes are limited).
- Not using mail.com or other IMAP providers anymore.
- Name: Driftbox. Normal-email-app interface: login first, small Register link at the bottom.
- Account switcher button goes at the top right when there is more than one address.
- All limits are listed in the About screen so other users can read them.
- Mail.tm credit goes in About, not on the login or inbox screens (decided Nov 16).

## Known limits (also shown in the About screen)

- Receive only (no compose, no Sent).
- No password recovery.
- Polling every 10 seconds, no background notifications.
- Attachments can be saved but not previewed. Links ask for confirmation first. Verification codes are guessed from text (a number of 4-8 digits with a word like "code" or "verify" just before it), so some can be missed.
- Addresses can be removed by Mail.tm at any time; mail is not private.
- If Mail.tm objects to the credit being only in About, move the link back to the main screen.

## How to use this file

When a step is done: tick its box, move the ">>>" marker to the next milestone, update "Next action", and bump the blueprint version. Send the whole file back each time, so there is always one up-to-date copy.

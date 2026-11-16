# Driftbox: Project Blueprint

Blueprint version: 3 (Nov 16, 2026)
Repo: https://github.com/comadrez/mailapp
App id: com.comadrez.mailapp (app name "Driftbox")

## Where we are

**>>> v0.4 Driftbox: written, waiting for the first build and phone test <<<**

Next action: push v0.4, wait for the green check in Actions, install the APK, and run the test checklist below.

## Milestones

- [x] **v0.1** mail.com IMAP/SMTP client. Dropped: mail.com IMAP/SMTP is Premium-only.
- [x] **v0.2** Lucide in-app icons (ISC) and an original adaptive app icon.
- [x] **v0.3** Pivot to temp mail with the free Mail.tm API (basic UI).
- [ ] **v0.4** Driftbox: renamed, login-first screen with a small Register link, Register screen (username, domain picker, password, random generator), account switcher at the top right when there are several addresses, encrypted storage, swipe to delete, filter chips, menu, About screen with all limits and the Mail.tm credit. **<- we are here**
- [ ] **v0.5** Fix pass from the first real test (build errors, API surprises, UI bugs).
- [ ] **v0.6** Quality of life: tappable links, one-tap copy of verification codes, attachment download, pull-to-refresh (needs a newer Compose library version).
- [ ] **v0.7** Live updates (SSE) instead of 10 second polling, and new-mail notifications.
- [ ] **v0.8** Extras: address expiry reminder, auto-delete of old addresses, maybe a second provider (mail.gw) as an option.
- [ ] **v1.0** Release: release signing, final polish, short privacy note.

## v0.4 test checklist

- [ ] GitHub Actions build is green
- [ ] APK installs over the old app and opens as "Driftbox"
- [ ] First launch shows the login screen with a small Register link at the bottom
- [ ] Register: domain list loads, "Generate" fills username and password, "Create address" works
- [ ] Inbox opens after registering, address shown under "Inbox"
- [ ] Menu > Copy address copies it
- [ ] An email sent to the address shows up within about 10 seconds
- [ ] Opening it shows the text and makes it normal (not bold)
- [ ] Swipe a message left or right deletes it
- [ ] Unread filter works
- [ ] Menu > Add address, register or sign in a second address: the switcher icon appears at the top right
- [ ] Switching between addresses works
- [ ] Sign out, then sign in again with address and password
- [ ] Menu > Delete this address works
- [ ] Menu > About and limits shows the limits, and "Powered by mail.tm" opens mail.tm

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

- Kotlin, Jetpack Compose, OkHttp, AndroidX security-crypto.
- Built by GitHub Actions (debug APK, no signing keys).
- Addresses and passwords are stored on the phone only, encrypted (EncryptedSharedPreferences).
- Files: `MailTm.kt` (API client), `MainActivity.kt` (state and screens), `res/drawable` (icons).

## Mail.tm API notes

- Base URL: https://api.mail.tm (no API key).
- Flow: GET /domains, POST /accounts, POST /token, GET /me, then GET /messages with a Bearer token.
- Also used: GET /messages/{id}, PATCH /messages/{id} (mark read), DELETE /messages/{id}, DELETE /accounts/{id}.
- No sending and no password reset in the API docs we read.
- Limit: 8 requests per second per IP.
- Terms: attribution link to mail.tm must stay visible (it lives in the About screen, reachable from the main menu; keep it reachable); no reselling, no proxying, no illegal use.
- Docs: https://docs.mail.tm

## Constraints and decisions

- Phone only (Termux), no PC.
- GitHub free plan: keep builds few; artifact retention can be shortened to 3 days.
- Icons: Lucide (ISC), credits in ICONS.md and the About screen.
- Not using mail.com or other IMAP providers anymore.
- Name: Driftbox. Normal-email-app interface: login first, small Register link at the bottom.
- Account switcher button goes at the top right when there is more than one address.
- All limits are listed in the About screen so other users can read them.
- Mail.tm credit goes in About, not on the login or inbox screens (decided Nov 16).

## Known limits (also shown in the About screen)

- Receive only (no compose, no Sent).
- No password recovery.
- Polling every 10 seconds, no background notifications.
- No attachments, links in emails are not tappable.
- Addresses can be removed by Mail.tm at any time; mail is not private.
- If Mail.tm objects to the credit being only in About, move the link back to the main screen.

## How to use this file

When a step is done: tick its box, move the ">>>" marker to the next milestone, update "Next action", and bump the blueprint version. Send the whole file back each time, so there is always one up-to-date copy.

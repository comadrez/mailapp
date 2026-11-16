# Temp Mail App: Project Blueprint

Blueprint version: 1 (Nov 16, 2026)
Repo: https://github.com/comadrez/mailapp
App id: com.comadrez.mailapp (label "Temp Mail")

## Where we are

**>>> v0.3: written, waiting for the first build and phone test <<<**

Next action: push v0.3, wait for the green check in Actions, install the APK, and test the checklist below.

## Milestones

- [x] **v0.1** mail.com IMAP/SMTP client (login, inbox, unread, read, send). Dropped: mail.com IMAP/SMTP is Premium-only.
- [x] **v0.2** Lucide in-app icons (ISC) and an original adaptive app icon.
- [ ] **v0.3** Pivot to temp mail with the free Mail.tm API. Create address, inbox, read, delete, multiple addresses, "Powered by mail.tm" link. **<- we are here**
- [ ] **v0.4** Fix pass from the first real test (build errors, API surprises, UI bugs).
- [ ] **v0.5** Quality of life: tappable links, one-tap copy of verification codes, attachment download, share address.
- [ ] **v0.6** Live updates: real-time events (SSE) instead of 10 second polling, and new-mail notifications.
- [ ] **v0.7** Extras: address expiry reminder, domain picker, optional auto-delete of old addresses.
- [ ] **v1.0** Release: final name and icon, release signing, short privacy note.

## v0.3 test checklist

- [ ] GitHub Actions build is green
- [ ] APK installs and opens
- [ ] "Create temporary address" makes an address
- [ ] Copy button copies the address
- [ ] An email sent to the address shows up within about 10 seconds
- [ ] Opening it shows the text and clears the unread dot
- [ ] Delete message works
- [ ] Custom name works, and a taken name shows an error
- [ ] A second address can be created and switched to
- [ ] Deleting an address works
- [ ] "Powered by mail.tm" opens mail.tm

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

- Kotlin, Jetpack Compose, OkHttp. No other network libraries.
- Built by GitHub Actions (debug APK, no signing keys).
- Addresses and passwords are stored on the phone only (SharedPreferences).
- Files: `MailTm.kt` (API client), `MainActivity.kt` (state and screens), `res/drawable` (icons).

## Mail.tm API notes

- Base URL: https://api.mail.tm (no API key).
- Flow: GET /domains, POST /accounts, POST /token, then GET /messages with a Bearer token.
- Also used: GET /messages/{id}, PATCH /messages/{id} (mark read), DELETE /messages/{id}, DELETE /accounts/{id}.
- Limit: 8 requests per second per IP.
- Terms: attribution link to mail.tm must stay visible; no reselling, no proxying, no illegal use.
- Docs: https://docs.mail.tm

## Constraints and decisions

- Phone only (Termux), no PC.
- GitHub free plan: keep builds few; artifact retention can be shortened to 3 days.
- Icons: Lucide (ISC), credits in ICONS.md.
- Not using mail.com or other IMAP providers anymore.

## Known limits (v0.3)

- Polling every 10 seconds, not live.
- No attachments, links in emails are not tappable.
- Domain is chosen automatically (first one offered).
- Temp addresses can be removed by Mail.tm at any time.

## How to use this file

When a step is done: tick its box, move the ">>>" marker to the next milestone, update "Next action", and bump the blueprint version. Send the whole file back each time, so there is always one up-to-date copy.

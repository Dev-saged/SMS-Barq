# Project rules (read first, every session)

Owner: Sajed. Reply to him in Arabic. Identifiers, syntax and commits in English.
Output: no greetings or filler. Max 3 bullets of explanation. One-line summary before code.

## EDIT RULES (strict)
- Never rewrite, regenerate or reformat a whole file. Use targeted edits only.
- Before editing: read docs/repo-map.md, then open only the relevant SECTION.
- State which sections you will touch (one line), then edit. Touch nothing else.
- No refactors, renames or style changes unless I ask.
- Preserve every existing feature, element ID, function name and CONFIG key.
- After editing: run the #test route if present, update repo-map.md in 2 lines, commit on a feature branch with a clear message.
- If a change exceeds 150 changed lines, stop and propose a plan first.
- Never delete or change an existing feature without telling me first and getting approval.
- Never reintroduce a bug listed in repo-map.md under "Solved bugs".

## Workflow
- Feature request: plan first, wait for approval, then implement on branch feature/<name>.
- New project: deliver a static prototype (home screen, 3 directions, sample data, no logic) and stop for my choice. Never rebuild the shell after approval.
- Session start: read .claude/session-handoff.md and docs/repo-map.md only. Do not scan the repo.
- Session end: rewrite (not append) .claude/session-handoff.md, max 25 lines: state, decisions, open issues, next step.
- Version: bump APP_VERSION and version.json (vMAJOR.MINOR.PATCH) on substantive changes.

## Stack
- Native Android: Kotlin + Jetpack Compose, minSdk 26, compileSdk 36, targetSdk 36. No WebView, no WebToApp.
- APK built by GitHub Actions (.github/workflows/build.yml); signing via KEYSTORE_BASE64 + SIGNING_* secrets (أو keystore.properties في المستودع الخاص).
- DI: Hilt + KSP. Serialization: kotlinx.serialization. Offline first: كل network call في try/catch.
- رفع المشروع zip من الهاتف مدعوم: الـworkflow يفكّه تلقائياً ويكوّم.

## Architecture
- VERSION_NAME + VERSION_CODE في gradle.properties. versionCode = BASE_VERSION_CODE + GITHUB_RUN_NUMBER عند البناء.
- In-app update: تحقق من "latest-build" GitHub Release; قارن versionCode.
- كل شاشة Composable مستقلة تحت ui/ (inbox, thread, compose, contacts, starred, batch, lock, onboarding, settings).
- SMS engine: حزمة sms/ (SendEngine, SendQueue, SendDrainer, SendTracker, SendLimit, ScheduleManager, gateway/). MMS: حزمة mms/.

## Design and security
- Design work: load skill design-system first.
- أيقونات: vectors يدوية مرسومة داخل الـComposables مباشرة. لا emoji ولا icon packs.
- Contrast >= 4.5:1, touch targets >= 44dp, visible focus, respect prefers-reduced-motion.
- Sanitize all input. لا تُعرّض أسرار التوقيع في الـlogs أو commit messages.

## Code hygiene
- No comments that reveal AI authorship. No purposeless lines. Comments only when necessary, short Arabic.
- Complete deployable code, no TODOs.

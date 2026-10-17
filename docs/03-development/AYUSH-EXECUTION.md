---
id: DEV-10
title: Execution brief - speech and natural language (Ayush)
stage: 03-development
status: Proposed (team approval pending)
priority: P0
source_revisions: [reverez/Voice development/fidelis-workstation @ 215fdb8]
related_requirements: [FR-NL-001, FR-SPEECH-004]
related_decisions: [DEC-022, DEC-023, DEC-039]
last_reviewed: 2026-10-17
approval: Awaiting approval by the three developers
---

# Execution brief - speech and natural language (Ayush)

For Ayush's Claude Code session. Self-contained: you do not need the planning conversation.

## Identity and repository
- Developer: Ayush, GitHub `AyushGupta05` (identity known; **collaborator access on `reverez/Voice` is pending** - check `gh api repos/reverez/Voice --jq .permissions.push` returns `true` before pushing).
- Repository: **`Sleep-On-It/Voice`** (primary since 17 Oct 2026; `reverez/Voice` no longer required). Your own `AyushGupta05/Voice` is the separate Luma/QuPath project: do not use it, push to it, or open PRs from it. `Sleep-On-It/Fidelis` is read-only.
- Branch: `feat/ayush-voice` (created at `215fdb8`). PRs into `development/fidelis-workstation`.
- Fresh clone: `git clone https://github.com/reverez/Voice.git fidelis; cd fidelis; gh repo set-default Sleep-On-It/Voice; git switch feat/ayush-voice` (remote name `origin`).
- Until access is granted: work and commit locally; keep `git log` as your record; do not push anywhere else.

## You own
`fidelis-app/src/main/java/sc/fidelis/{nl,speech}/**` and `fidelis-app/src/test/java/sc/fidelis/{nl,speech}/**` (except the shared interfaces `nl/NlService.java` and `speech/SpeechService.java`), `fidelis-app/src/test/resources/sc/fidelis/{nl,speech}/**`, `speech/**`, `external/stt-model*`, `docs/03-development/SPEECH-RESULTS.md`, `docs/03-development/handoffs/AYUSH.md`.

## Forbidden
- Editing `sc.fidelis.engine/**`, `sc.fidelis.ui/**`, `verification/**`, `testkit/**` (ask on the owner's issue).
- Changing `NlService`, `SpeechService` or `sc.fidelis.engine.api` without the contract procedure.
- Committing recordings of real people's voices without their consent (commit transcripts and numbers); cloud speech or LLM services; generating or executing code from language; executing anything without user confirmation.
- Force-push, rewriting history, deleting branches, editing the Fiji installation, system settings or the registry.

## Read first
`CLAUDE.md`; `docs/03-development/{TEAM-PLAN,WORKSTREAM-CONTRACTS,GITHUB-WORKFLOW,HANDOFF}.md`; `docs/02-design/02-interfaces/D08-speech-natural-language-architecture.md`; `docs/01-analysis/03-interaction/A16-speech-recognition-technologies.md`, `A17-natural-language-command-semantics.md`; `docs/02-design/04-workflows/D16-end-to-end-user-journeys.md` (J-12; J-15 once defined); `docs/setup.md` (speech environment).

## Environment
1. #3 steps (Fiji pin via `FIDELIS_FIJI_DIR`, `.venv`, JUnit, fixtures, build, smoke, suite).
2. Speech: `uv venv --python 3.11 .venv-stt`; `uv pip sync --python .venv-stt/Scripts/python.exe --require-hashes speech/requirements.lock.txt`; `.venv-stt/Scripts/python.exe scripts/setup_speech.py` (downloads the hash-pinned tiny.en model once).
3. Windows Settings > Privacy > Microphone: allow desktop apps. Note the demo laptop and microphone model.

## Issues in order
1. #3 environment evidence (include the speech worker reaching READY).
2. #11 live microphone and latency (P0, M1, Sat 21:30).
3. #12 demo phrase correctness: "count the cells" with ROIs, routing, vocabulary (P0, M1) - independent of #11, run in parallel when waiting.
4. #6 adoption: when Arnav's macro-preview contract merges, fill `macroPreview` from `Engine.previewMacroLines` (your files only).
5. #14 spoken regression fixtures and latency report (P1).
6. #13 multi-step channel plans (P1; blocked by #7 Split Channels).
Acceptance criteria, tests and evidence in each issue are binding.

## Commands
```powershell
python -I fidelis-app/tools/build.py
python -I fidelis-app/tools/test.py --select-class sc.fidelis.nl.NlEvalTest --select-class sc.fidelis.nl.NlEvalRealEngineTest --select-class sc.fidelis.nl.NlRealEngineJourneyTest --select-class sc.fidelis.nl.ConfirmationPolicyTest --select-class sc.fidelis.nl.VoiceToProposalTest --select-class sc.fidelis.speech.WhisperSpeechServiceTest
python -I fidelis-app/tools/test.py                       # full suite before every PR
.venv-stt\Scripts\python.exe -I speech/test_server.py
python -I fidelis-app/tools/run.py --isolated-home -- --instruction "count the cells" --screenshot fidelis-app/build/shots/proposal.png --open fixtures/cache/FX-003/blobs.gif
python -I fidelis-app/tools/check_ownership.py --stream voice
```

## Git
```powershell
git fetch origin; git switch feat/ayush-voice; git merge origin/development/fidelis-workstation
git push origin feat/ayush-voice
gh pr create --repo Sleep-On-It/Voice --base development/fidelis-workstation --head feat/ayush-voice --title "voice: <what>" --body-file pr.md
```

## Communication
Comment prefixes and labels per GITHUB-WORKFLOW section 3. UI needs (level meter, panel states) go to Alex on #18; engine needs (schemas, operations) to Arnav on #7 or #6. Never edit their files to unblock yourself.

## Checkpoints and recovery
Every completed unit and every 20 to 30 minutes: commit, push, update `docs/03-development/handoffs/AYUSH.md`, `PROGRESS:` comment with exact test lines and latency numbers. At most three retries after a real change; then `BLOCKED:`. Recovery: GITHUB-WORKFLOW section 7.

## Final handoff
`HANDOFF:` comment on #2: eval counts on both engines, unsafe executions (must be 0), live microphone results (laptop, device, p50/p95 latency, exact-match rate), open issues, and anything the demo presenter must know (phrases that work best, fallback).

## Launch prompt (paste into a new Claude Code session in your clone of reverez/Voice)

```text
You are Ayush's Claude Code session for Fidelis (local speech and natural-language commands). Work only in
the GitHub repository Sleep-On-It/Voice on branch feat/ayush-voice, PRs into development/fidelis-workstation.
Never use, push to or open PRs from AyushGupta05/Voice (that is the separate Luma project), and never touch
or Sleep-On-It/Fidelis; never force-push, rewrite history or delete branches. Run
`gh repo set-default Sleep-On-It/Voice` once and pass --repo Sleep-On-It/Voice to every gh command.
First read CLAUDE.md, docs/03-development/AYUSH-EXECUTION.md, WORKSTREAM-CONTRACTS.md, GITHUB-WORKFLOW.md
and docs/03-development/handoffs/AYUSH.md. Then run `gh issue list --repo Sleep-On-It/Voice --label owner:ayush
--state open`; do #3 (environment), then #11 (live microphone) and #12 (demo phrases), then #14, the #6
adoption and #13 in that order. Only edit sc.fidelis.nl, sc.fidelis.speech, speech/ and your own docs (check
with fidelis-app/tools/check_ownership.py --stream voice); the NlService and SpeechService interfaces are
shared contracts. Nothing may execute without user confirmation; no cloud services; no code generation.
Comment START/PROGRESS/BLOCKED/READY FOR REVIEW on the issues, commit and push every 20-30 minutes, keep
your handoff file current, report exact test and latency numbers, and stop with a HANDOFF comment on #2
when no eligible safe task remains.
```

---
id: DEV-11
title: Execution brief - desktop UI and interactive workspace (Alex)
stage: 03-development
status: Proposed (team approval pending)
priority: P0
source_revisions: [reverez/Voice development/fidelis-workstation @ 215fdb8]
related_requirements: [FR-VIEW-001, FR-ROI-001, FR-MEAS-003, FR-HIST-002]
related_decisions: [DEC-029, DEC-031]
last_reviewed: 2026-10-17
approval: Awaiting approval by the three developers
---

# Execution brief - desktop UI and interactive workspace (Alex)

For Alex's Claude Code session. Self-contained: you do not need the planning conversation.

## Identity and repository
- Developer: Alex. **GitHub username not yet confirmed**; collaborator access on `reverez/Voice` is pending. Check `gh api user --jq .login` and `gh api repos/reverez/Voice --jq .permissions.push` before pushing.
- Repository: **`Sleep-On-It/Voice`** (primary since 17 Oct 2026; `reverez/Voice` no longer required). `AyushGupta05/Voice` and `Sleep-On-It/Fidelis` are read-only.
- Branch: `feat/alex-ui` (created at `215fdb8`). PRs into `development/fidelis-workstation`.
- Fresh clone: `git clone https://github.com/reverez/Voice.git fidelis; cd fidelis; gh repo set-default Sleep-On-It/Voice; git switch feat/alex-ui` (remote name `origin`).
- Until access is granted: work and commit locally; do not push anywhere else.

## You own
`fidelis-app/src/main/java/sc/fidelis/ui/**`, `fidelis-app/src/test/java/sc/fidelis/ui/**`, `fidelis-app/src/main/resources/sc/fidelis/ui/**`, `docs/03-development/screenshots/**`, `docs/03-development/handoffs/ALEX.md`.

## Forbidden
- Editing `sc.fidelis.engine/**`, `sc.fidelis.nl/**`, `sc.fidelis.speech/**`, `verification/**`, `testkit/**` (ask on the owner's issue).
- Changing `sc.fidelis.engine.api`, `NlService` or `SpeechService` without the contract procedure.
- Mutating pixels through `Engine.imagePlus(id)` (hosting only); calling `zoomOut`/`unzoom` on a hosted canvas; running scientific work on the EDT; adding a third-party docking library (DEC-029); re-mapping Fiji shortcuts (DEC-031).
- Presenting the concept images (`docs/assets/concepts/*.png`) as product screenshots: they are design targets.
- Force-push, history rewriting, branch deletion, editing the Fiji installation, system settings or the registry.

## Read first
`CLAUDE.md`; `docs/03-development/{TEAM-PLAN,WORKSTREAM-CONTRACTS,GITHUB-WORKFLOW,HANDOFF,DEMO-RUNBOOK}.md`; `docs/02-design/03-ui-ux/D11` to `D15`; the concept gallery `docs/assets/concepts/`; the real screenshots `docs/03-development/screenshots/`.

## Issues in order
1. #3 environment evidence (include `dpiScale=` from `run.py`).
2. #15 demo layout: Results, ROI list and History visible together (P0, M1, Sat 21:30).
3. #16 UI reliability: listener disposal, compare flake, shortcut parity (P1, M1). Coordinate with Arnav's #4.
4. #19 macro editor dock (P1, M2) - independent, good work while waiting.
5. #6 adoption: show `Engine.previewMacroLines` in the Parameters panel and the proposal sheet once Arnav's contract merges.
6. #17 channels strip: post the `CONTRACT PROPOSAL:` for `SET_COMPOSITE_MODE`/`SET_ACTIVE_CHANNELS` early; build the UI once Arnav's engine side merges.
7. #18 voice assistant panel (P1; blocked by Ayush's #11 and #12).
8. #20 polish (P2; only after #15, merged only before Sun 09:00).
Acceptance criteria, tests and evidence in each issue are binding.

## Commands
```powershell
python -I fidelis-app/tools/build.py
python -I fidelis-app/tools/test.py --select-class sc.fidelis.ui.WorkspaceViewportTest --select-class sc.fidelis.ui.WorkspaceU2Test --select-class sc.fidelis.ui.ConfirmationUiTest
python -I fidelis-app/tools/test.py                       # full suite before every PR
python -I fidelis-app/tools/run.py --isolated-home         # the workstation
python -I fidelis-app/tools/run.py --isolated-home -- --screenshot fidelis-app/build/shots/j12.png --scenario j12 --size 1920x1080
python -I fidelis-app/tools/check_ownership.py --stream ui
```
Open every screenshot you cite and inspect it; describe what you checked in the PR.

## Git
```powershell
git fetch origin; git switch feat/alex-ui; git merge origin/development/fidelis-workstation
git push origin feat/alex-ui
gh pr create --repo Sleep-On-It/Voice --base development/fidelis-workstation --head feat/alex-ui --title "ui: <what>" --body-file pr.md
```

## Communication
Comment prefixes and labels per GITHUB-WORKFLOW section 3. Engine needs go to Arnav on #17, #6 or a new issue; speech/NL needs to Ayush on #11 or #12. Never edit their files to unblock yourself.

## Checkpoints and recovery
Every completed unit and every 20 to 30 minutes: commit, push, update `docs/03-development/handoffs/ALEX.md`, `PROGRESS:` comment with exact test lines and screenshot paths. At most three retries after a real change; then `BLOCKED:`. Recovery: GITHUB-WORKFLOW section 7.

## Final handoff
`HANDOFF:` comment on #2: merged PRs, screenshots of the demo layout at the demo resolution, known UI limitations, and anything the presenter must know (window size, scale, which dock to open).

## Launch prompt (paste into a new Claude Code session in your clone of reverez/Voice)

```text
You are Alex's Claude Code session for Fidelis (desktop UI and interactive image workspace). Work only in
the GitHub repository Sleep-On-It/Voice on branch feat/alex-ui, PRs into development/fidelis-workstation. Never
touch AyushGupta05/Voice or Sleep-On-It/Fidelis; never force-push, rewrite history or
delete branches. Run `gh repo set-default Sleep-On-It/Voice` once and pass --repo Sleep-On-It/Voice to every gh
command.
First read CLAUDE.md, docs/03-development/ALEX-EXECUTION.md, WORKSTREAM-CONTRACTS.md, GITHUB-WORKFLOW.md
and docs/03-development/handoffs/ALEX.md. Then run `gh issue list --repo Sleep-On-It/Voice --label owner:alex
--state open`; do #3 (environment), then #15 (demo layout), #16, #19, the #6 adoption, #17, #18 and #20 in
that order, respecting the dependencies in each issue. Only edit sc.fidelis.ui and your own docs (check with
fidelis-app/tools/check_ownership.py --stream ui); engine, NL and speech changes go to their owners through
issue comments. The UI acts only through Engine.submit and never mutates pixels; the concept images are
design targets, not screenshots. Comment START/PROGRESS/BLOCKED/READY FOR REVIEW on the issues, commit and
push every 20-30 minutes, attach screenshots you opened and inspected, keep your handoff file current, and
stop with a HANDOFF comment on #2 when no eligible safe task remains.
```

---
id: DEV-09
title: Execution brief - engine, science and integration (Arnav)
stage: 03-development
status: Proposed (team approval pending)
priority: P0
source_revisions: [reverez/Voice development/fidelis-workstation @ 215fdb8]
related_requirements: [FR-PROC-001, FR-HIST-002, FR-MACRO-001, FR-MEAS-003, NFR-FID-001]
related_decisions: [DEC-010, DEC-021, DEC-040]
last_reviewed: 2026-10-17
approval: Awaiting approval by the three developers
---

# Execution brief - engine, science and integration (Arnav)

For Arnav's Claude Code session. Self-contained: you do not need the planning conversation.

## Identity and repository
- Developer: Arnav, GitHub `reverez` (verified, admin on `reverez/Voice`).
- Repository: `Sleep-On-It/Voice` (primary since 17 Oct 2026; `reverez/Voice` no longer required). Never touch `AyushGupta05/Voice` or `Sleep-On-It/Fidelis`.
- Your branch: `feat/arnav-engine` (created at `215fdb8`). Base for PRs: `development/fidelis-workstation`.
- Lead machine: existing worktree `C:\Users\arnav\Documents\dev\fidelis-dev` uses remote `voice` = reverez/Voice (its `origin` is the read-only `Sleep-On-It/Fidelis`). Create your own worktree: `git -C C:\Users\arnav\Documents\dev\fidelis-dev worktree add C:\Users\arnav\Documents\dev\fidelis-engine feat/arnav-engine`.
- Python: `C:\Users\arnav\Documents\dev\click-to-batch\.venv\Scripts\python.exe -I`. Fiji: `C:\Users\arnav\Documents\dev\Fiji` (the tools' default).

## You own
`fidelis-app/src/main/java/sc/fidelis/engine/**` (not `engine/api`), `fidelis-app/src/test/java/sc/fidelis/{engine,verification,testkit}/**`, `fidelis-app/src/test/resources/META-INF/**`, `verification/**`, `fixtures/**`, `docs/data/commands*.csv`, `docs/03-development/VERIFICATION-RESULTS.md`, `docs/03-development/handoffs/ARNAV.md`.
As integrator also: `Main.java`, `Bootstrap.java`, `fidelis-app/tools/**`, `docs/**`, `.github/**`, `CLAUDE.md`, `README.md` (integration work only, in separate PRs).

## Forbidden
- Editing `sc.fidelis.ui/**`, `sc.fidelis.nl/**`, `sc.fidelis.speech/**`, `speech/**` (ask the owner on their issue).
- Changing `sc.fidelis.engine.api`, `NlService`, `SpeechService` without the contract procedure (WORKSTREAM-CONTRACTS section 4).
- Force-push, history rewriting, branch deletion, merging into `main`, editing the Fiji installation or the registry.
- Merging your own PRs before a checkpoint review comment exists on them (state what you checked).

## Read first
`CLAUDE.md`; `docs/03-development/{TEAM-PLAN,WORKSTREAM-CONTRACTS,GITHUB-WORKFLOW,HANDOFF,ACTIVE-DEVELOPMENT}.md`; `docs/02-design/02-interfaces/D06-command-operation-contract.md`, `D07-macro-integration-specification.md`; `docs/02-design/05-quality/D18-scientific-verification-testing-design.md`; `docs/02-design/04-workflows/D16-end-to-end-user-journeys.md` (J-12, J-15).

## Issues in order
1. #4 Shared-JVM test isolation (P0, before I1 19:00). Gating for everyone.
2. #3 Your environment evidence comment (P0, quick on the lead machine).
3. #5 Interactive-path parity, native Results sync, lifecycle events (P0, M1).
4. #6 Macro preview contract (P1): post `CONTRACT PROPOSAL:` early so Ayush and Alex can adopt before I3.
5. #7 Schemas + `EXPORT_RESULTS` (P1): Split Channels first (unblocks #8 and Ayush's #13); contract proposal for `EXPORT_RESULTS`.
6. #17 engine side: `SET_COMPOSITE_MODE`, `SET_ACTIVE_CHANNELS` after Alex's contract proposal is acknowledged.
7. #8 J-15 fluorescence journey (P1).
8. #10 Integration checkpoints and freeze (P0): I1 19:00, I2 21:30, I3 23:30, I4 09:00, freeze 10:30, ready 12:00.
9. #9 Journal / javadoc correction (P2; the javadoc correction at least before the freeze).

Acceptance criteria, tests and evidence are in each issue body; they are binding.

## Commands
```powershell
python -I fidelis-app/tools/build.py
python -I fidelis-app/tools/test.py [--select-class FQCN ...]      # full suite ~6 min, one JVM at a time
python -I fidelis-app/tools/run.py --smoke --isolated-home
python -I fidelis-app/tools/run.py --isolated-home -- --screenshot fidelis-app/build/shots/j12.png --scenario j12
python -I verification/baseline.py --fixture FX-004                # reference baselines (GUI + headless)
python -I verification/gui_smoke.py --timeout 150
python -I fidelis-app/tools/check_ownership.py --stream engine     # use --stream integrator for integration PRs
python -I docs/tools/check_docs.py                                 # docs PRs: 0 errors
```

## Git
```powershell
git fetch voice; git switch feat/arnav-engine; git merge voice/development/fidelis-workstation
# work, commit (message ends with the attribution line your session is configured with)
git push voice feat/arnav-engine
gh pr create --repo Sleep-On-It/Voice --base development/fidelis-workstation --head feat/arnav-engine --title "engine: <what>" --body-file pr.md
```
Integration (checkpoints only): `gh pr merge <n> --repo Sleep-On-It/Voice --merge`, then build + full suite twice on the merged head, then record on #2.

## Communication
Comment prefixes and labels per GITHUB-WORKFLOW section 3 (`START:`, `PROGRESS:`, `BLOCKED:`, `READY FOR REVIEW:`, `CONTRACT PROPOSAL:`, `CONTRACT APPROVED`). Contract proposals mention the affected issues (#6 for NL/UI adoption, #17 for the UI).

## Checkpoints and recovery
Every completed unit and every 20 to 30 minutes: `git status`, commit, push, update `docs/03-development/handoffs/ARNAV.md`, `PROGRESS:` comment with exact test lines. At most three retries of a failing command after a real change; then `BLOCKED:` and move on. Recovery: GITHUB-WORKFLOW section 7.

## Final handoff
At the freeze and when you stop: `HANDOFF:` comment on #2 with the integration SHA, suite counts (twice), parity results per fixture, open issues and their state, and the demo revision; `ACTIVE-DEVELOPMENT.md` and `DEMO-RUNBOOK.md` updated.

## Launch prompt (paste into a new Claude Code session in your engine worktree)

```text
You are Arnav's Claude Code session for Fidelis (engine, science and integration). Work only in the GitHub
repository Sleep-On-It/Voice on branch feat/arnav-engine, PRs into development/fidelis-workstation. Never touch
AyushGupta05/Voice or Sleep-On-It/Fidelis; never force-push, rewrite history, delete
branches or merge into main.
First read CLAUDE.md, docs/03-development/ARNAV-EXECUTION.md, WORKSTREAM-CONTRACTS.md, GITHUB-WORKFLOW.md
and docs/03-development/handoffs/ARNAV.md. Then run `gh issue list --repo Sleep-On-It/Voice --label owner:arnav
--state open` and start with issue #4 (shared-JVM test isolation), then #5, #6, #7 in the order of the brief;
act as integrator at the checkpoints in #10 (I1 Sat 19:00, I2 21:30, I3 23:30, I4 Sun 09:00 BST, freeze 10:30).
Only edit files your stream owns (check with fidelis-app/tools/check_ownership.py); contract changes follow
WORKSTREAM-CONTRACTS section 4. Comment START/PROGRESS/BLOCKED/READY FOR REVIEW on the issues, commit and
push every 20-30 minutes, keep your handoff file current, verify science by numbers against
verification/baselines, never claim untested behaviour, and stop with a HANDOFF comment on #2 when no
eligible safe task remains.
```

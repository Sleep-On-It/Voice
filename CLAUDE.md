# Fidelis — instructions for Claude Code sessions

Fidelis modernises the **real Fiji/ImageJ**: one persistent Fiji engine (`fidelis-app/`, Java 21, Swing +
FlatLaf) with real IJ1 canvases, real commands, real macros, and local speech/typed instructions that only
*propose* operations for confirmation. It is not a reimplementation of image processing.

## Hard rules
- The primary GitHub repository is **`Sleep-On-It/Voice`** (changed 17 Oct 2026; `reverez/Voice` is no longer required).
  `AyushGupta05/Voice` (Ayush's separate Luma project) and `Sleep-On-It/Fidelis` (the preserved original) stay
  read-only. Pass `--repo Sleep-On-It/Voice` to every `gh` command.
- One branch per developer (`feat/arnav-engine`, `feat/ayush-voice`, `feat/alex-ui`); PRs into
  `development/fidelis-workstation`; only the integrator (Arnav) merges. Never force-push, rewrite pushed
  history, delete branches or touch `main`.
- Edit only files your workstream owns: `python -I fidelis-app/tools/check_ownership.py --stream <engine|voice|ui>`.
  Shared contracts (`sc.fidelis.engine.api`, `NlService`, `SpeechService`) change only through a
  `needs:contract-review` issue (docs/03-development/WORKSTREAM-CONTRACTS.md section 4).
- Never modify the Fiji installation, the Windows registry or system-wide Java settings; run the app with
  `--isolated-home`; one Fiji JVM per worktree at a time.
- Never claim behaviour you did not run. Scientific results are verified by numbers against
  `verification/baselines` (pinned Fiji), not by appearance. Never weaken or disable a test to go green.
- Speech/NL never executes anything without user confirmation; no code generation from language; no cloud
  services. The concept images in `docs/assets/concepts/` are design targets, not screenshots.

## Start here
1. `docs/03-development/<ARNAV|AYUSH|ALEX>-EXECUTION.md` (your brief and launch instructions).
2. `docs/03-development/handoffs/<NAME>.md` (where the last session stopped).
3. `docs/03-development/GITHUB-WORKFLOW.md` (issue lifecycle, PRs, checkpoints, retries, recovery).
4. `docs/03-development/WORKSTREAM-CONTRACTS.md` (ownership, invariants, contract reference).
5. `docs/03-development/TEAM-PLAN.md` and the master issue #2 (schedule, risks, checkpoints).

## Toolchain (Windows; Python 3.11+ `-I`; Fiji via `FIDELIS_FIJI_DIR`)
```
python -I fidelis-app/tools/fetch_junit.py      # once per clone
python -I fidelis-app/tools/build.py            # javac with Fiji's bundled JDK (no Maven)
python -I fidelis-app/tools/run.py --isolated-home [-- --screenshot <png> --scenario j12]
python -I fidelis-app/tools/run.py --smoke --isolated-home
python -I fidelis-app/tools/test.py [--select-class FQCN ...]   # JUnit 5 in a live Fiji JVM; full run ~6 min
python -I docs/tools/check_docs.py              # documentation check (0 errors)
```

## Checkpoints
Every completed unit and every 20–30 minutes: commit, push your branch, update your handoff file, comment
`PROGRESS:` on the issue with exact test results. After three failed attempts at the same thing, comment
`BLOCKED:` and move to another ready issue.

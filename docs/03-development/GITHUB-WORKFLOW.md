---
id: DEV-08
title: GitHub workflow for the three workstreams
stage: 03-development
status: Proposed (team approval pending)
priority: P0
source_revisions: [reverez/Voice, development/fidelis-workstation @ 215fdb8]
related_requirements: [NFR-MAINT-004, FR-HIST-002]
related_decisions: [DEC-048]
last_reviewed: 2026-10-17
approval: Awaiting approval by the three developers
---

# GitHub workflow for the three workstreams

Written for the developers and for every Claude Code session working on Fidelis. GitHub is the only
shared memory between sessions: issues, comments, pull requests and the files in the repository. Do not
assume another session can see your conversation.

## 1. Repositories (the one rule that must never be broken)

- The primary repository is **`Sleep-On-It/Voice`** (changed 17 Oct 2026; `reverez/Voice` is no longer required). Ayush's `AyushGupta05/Voice` (Luma, a separate project) and `Sleep-On-It/Fidelis` stay read-only.
- In every clone run `gh repo set-default Sleep-On-It/Voice` once, and pass `--repo Sleep-On-It/Voice` explicitly to every `gh pr` and `gh issue` command.
- Before any push: `git remote -v` must show `https://github.com/Sleep-On-It/Voice.git` for the remote you push to.
- Remote names: `<remote>` below means the remote that points at Sleep-On-It/Voice (`origin` in a fresh clone).

## 2. Branches

| Branch | Purpose | Who pushes |
|---|---|---|
| `main` | untouched hackathon fork base | nobody |
| `development/fidelis-workstation` | integration branch and the shared baseline | the integrator, through merged PRs only |
| `feat/arnav-engine` | engine, science, verification | Arnav's session |
| `feat/ayush-voice` | speech and language | Ayush's session |
| `feat/alex-ui` | desktop UI | Alex's session |
| `plan/team-workstreams` | this planning change | the coordinator |

- All three feature branches start at `215fdb8` (the baseline). Never push to another developer's branch.
- A repository ruleset (`protect-history`) blocks force-pushes and deletion on `main`, `development/fidelis-workstation`, `feat/**` and `plan/**`. Never try to work around it.
- Stay current without rewriting history: `git fetch <remote>`, then `git merge <remote>/development/fidelis-workstation` into your branch. Never rebase a pushed branch, never `push --force`.
- Each developer uses their own clone or `git worktree`; one Fiji JVM per worktree at a time; `run.py --isolated-home`. Nobody edits the Fiji installation, the registry or system-wide Java preferences.

## 3. Issue lifecycle

Status labels stand in for a project board (GitHub Projects needs a token scope this account does not
have yet): `status:ready` -> `status:in-progress` -> `status:in-review` -> `status:integrated` ->
`status:verified`; `status:blocked` at any time.

| Moment | You do |
|---|---|
| Start | Pick the highest-priority `status:ready` issue of your stream whose dependencies are closed or `status:integrated`. Comment `START: <one-line plan>`. Relabel `status:in-progress` (if you lack label rights, say so in the comment and the integrator relabels). |
| During | Commit coherent increments; push at least every 30 minutes. Comment `PROGRESS: <done / next / tests>` at each meaningful step. Update your handoff file. |
| Blocked | Comment `BLOCKED: <issue> - <technical reason> - needs <dependency or contract> from <stream/issue> - suggested resolution: <...>`. Relabel `status:blocked`. Move to another ready issue. Never work around a dependency by editing another stream's files. |
| Done | Run the required tests, open the PR (section 4), comment `READY FOR REVIEW: <PR link>`, relabel `status:in-review`. Do not merge. |
| Integrated | The integrator merges, relabels `status:integrated`, ticks the master issue, and later `status:verified` after the checkpoint run. The issue closes through `Closes #n`. |

Comment prefixes are part of the protocol: `START:`, `PROGRESS:`, `BLOCKED:`, `READY FOR REVIEW:`,
`CONTRACT PROPOSAL:`, `CONTRACT ACK`, `CONTRACT APPROVED`, `HANDOFF:`. Keep comments factual: commands
run, results, file paths, SHAs.

## 4. Pull requests

```powershell
python -I fidelis-app/tools/check_ownership.py --stream <engine|voice|ui>
python -I fidelis-app/tools/build.py
python -I fidelis-app/tools/test.py            # full suite on your branch head
git push <remote> <your-branch>
gh pr create --repo Sleep-On-It/Voice --base development/fidelis-workstation --head <your-branch> --title "<area>: <what>" --body-file <file>
```

The PR template asks for: linked issue (`Closes #n`); scope and changed-file summary; the ownership
check output; tests run with their result lines; scientific parity evidence when science is touched;
screenshots that were opened and inspected for UI changes; real microphone evidence for any live-speech
claim; confirmation that no binaries, runtimes, models, datasets or credentials were added; contract
review references; documentation updated.

One purpose per PR. Small PRs merge at checkpoints; a PR that cannot be reviewed in ten minutes is too big.

## 5. Merge authority and order

- Only the integrator (Arnav) merges, with a merge commit (`gh pr merge <n> --repo Sleep-On-It/Voice --merge`). No squash of shared history, no force-push, no merges into `main`.
- At each checkpoint (I1 Sat 19:00, I2 Sat 21:30, I3 Sat 23:30, I4 Sun 09:00 BST) the order is: contract PRs, then engine, then voice, then UI. After each merge the next PR must be up to date with the integration branch (the author merges it in) and green.
- After the merged head passes the full suite twice, the integrator records the SHA and counts on the master issue (#2).
- Freeze at Sun 10:30 BST: only demo-blocking fixes, approved by all three in the master issue.

## 6. Definition of done (every issue)

1. PR into `development/fidelis-workstation` on `reverez/Voice` with `Closes #n`.
2. Ownership check passes, or each exception links an approved `needs:contract-review` issue.
3. `build.py` green; the issue's required tests green; the full suite green on the PR head (a pre-existing failure must link its issue).
4. Evidence in the PR: commands, outputs and numbers; for UI, screenshots that were actually opened and inspected; for science, parity against the reference baselines; for live speech, real microphone runs.
5. Handoff file updated; integrator merged; issue `status:integrated`.

A unit test passing is never presented as proof that a full scientific workflow is correct.

## 7. Autonomous sessions: checkpoints, retries and recovery

Checkpoint at every completed unit of work and roughly every 20 to 30 minutes:
1. `git status` (nothing important left untracked).
2. Commit with a message stating what was verified; push to your branch.
3. Update `docs/03-development/handoffs/<NAME>.md` (current issue, last green test line, next step, open questions, PR links).
4. `PROGRESS:` comment on the issue, reporting passing and failing tests exactly.

Retries: run a failing command at most **three** times after a real change of approach. Then record a
`BLOCKED:` comment with the exact error, stop that line of work and move to another ready issue. Never
loop on a failing command, never disable a test or a safety check to proceed.

Safety: no destructive Git (`reset --hard` on pushed work, `push --force`, branch deletion), no edits
outside your ownership, no credentials in files or logs, no paid services, no changes to the Fiji
installation, the registry or system settings, no live network services beyond downloading pinned
fixtures and models.

Recovery after an interrupted or context-exhausted session:
1. `git fetch <remote>`; `git switch <your-branch>`; `git status`; `git log --oneline -10`.
2. Read `CLAUDE.md`, your execution brief, and your handoff file.
3. `gh issue list --repo Sleep-On-It/Voice --label owner:<name> --state open` and read the last comments on your `status:in-progress` issue.
4. `gh pr list --repo Sleep-On-It/Voice --head <your-branch>`.
5. Rebuild and re-run the last tests before continuing. If the working tree holds uncommitted changes you cannot explain, commit them on a new branch `wip/<name>-<time>` and report it instead of discarding them.

When no eligible safe task remains: write a `HANDOFF:` comment on the master issue (what is done, what is
open, next eligible issue and why it is blocked) and stop.

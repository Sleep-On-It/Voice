# Penny

**Make scientific software conversational.** You keep using the real application; you talk, Penny acts in it, points at
what it did with its own cursor, and answers in one spoken sentence with the application's real numbers. Nothing is
mocked: every count comes out of the installed Fiji or QuPath.

Built for [Reinvent the Wheel: A Tools for Science Hack](https://luma.com/vdq0qw9u), London, 16–18 October 2026, by the
Sleep-On-It team. Inspired by [Clicky](https://github.com/farzaa/clicky).

The repository holds two things that are now one product:

- **Penny** (`packages/gateway`, `apps/companion`, `adapters/fiji`, `adapters/qupath`, `skills/`): hands-free voice
  (Amazon Transcribe → OpenAI `gpt-oss-120b` on Bedrock → Amazon Polly, AWS credits only), action-first tools for
  biological image analysis, a floating voice bar, an on-screen cursor, and application adapters. Simple commands run
  with no model round; everything else gets an immediate spoken acknowledgment while the action runs, then one short
  sentence with real numbers. Actions execute immediately and are journaled and checkpointed, so "undo that" works.
- **Fidelis** (`fidelis-app/`, `verification/`, `fixtures/`, `speech/`): a faithful modernisation of Fiji/ImageJ. The
  real Fiji runs unchanged in one persistent JVM behind a modern single-window workspace (real IJ1 canvases, Fiji's own
  menus and tools, parameter forms with the exact macro line, Results at full precision, history with undo). Every
  action goes through one engine; the structured history generates a standard ImageJ macro that classic Fiji replays,
  and the verification suite compares pixels, Results doubles and ROI vertices exactly against the pinned Fiji.

In the integrated system Penny's voice drives the Fidelis engine through a local bridge inside the Fidelis JVM, and the
Fidelis workspace shows the conversation, the plan and the highlights on its own canvas
([INTEGRATION.md](docs/INTEGRATION.md), [FIDELIS-PROTOCOL.md](docs/FIDELIS-PROTOCOL.md)).

![Fidelis workspace with Penny's answer flashed on the Blobs nuclei](docs/images/fidelis/penny-answer-flash.png)

## Three ways to run it

Each launcher writes the application profile to `.local/state/app`, so the companion's gateway picks the matching tools.

| Profile | Launch | What runs |
|---|---|---|
| **Stand-alone Fiji** (`LUMA_APP=fiji`) | `./script/run-fiji.sh`, then `./script/build_and_run.sh --verify` | The pinned Fiji with Penny's bridge plugin (57 bridge operations, 68 gateway tools: segmentation, measurement, thresholds, TrackMate, 25 verified plugin recipes, macros, original dialogs). [FIJI-SETUP.md](docs/FIJI-SETUP.md) · [DEMO-FIJI.md](docs/DEMO-FIJI.md) · [FIJI-PROTOCOL.md](docs/FIJI-PROTOCOL.md) |
| **Fidelis workstation** (`LUMA_APP=fidelis`) | `./script/run-fidelis.sh`, then `./script/build_and_run.sh --verify` | Builds Fidelis and starts it with Penny's bridge on port 49558 (30 operations, 33 gateway tools; every Fiji menu command and plugin through the catalogue, original dialogs included). Explicit plans wait for Confirm or the researcher's spoken "yes". [FIDELIS-DEMO.md](docs/FIDELIS-DEMO.md) · [INTEGRATION.md](docs/INTEGRATION.md) |
| **QuPath** (`LUMA_APP=qupath`) | `./script/run-qupath.sh`, then `./script/build_and_run.sh --verify` | The pinned QuPath 0.7.0 with Penny's extension (33 tools: context, objects, measurements, navigation, display, highlights, analysis, checkpoints, menus, JavaFX dialogs, reviewed Groovy). [PENNY-SETUP.md](docs/PENNY-SETUP.md) · [DEMO.md](docs/DEMO.md) |

The Fidelis workstation also runs on its own, without Penny: `python3 -I fidelis-app/tools/run.py --isolated-home`
(typed commands, the local Whisper push-to-talk and the deterministic interpreter with its proposal sheet).

## Setup

### macOS (Apple silicon): the whole system

Xcode command-line tools, Node.js 22+, Git and Python 3.11+ are required. The bootstrap verifies pinned official
downloads and keeps the JDK, QuPath, Fiji, upstream source and documentation in `.local`; it installs nothing globally
and modifies nothing inside the downloaded applications except the bridge plugin copied into Fiji's `plugins/`.

```sh
python3 script/setup.py                 # JDK 25, QuPath 0.7.0, upstream source and docs, npm install
python3 script/setup-fiji.py            # the pinned Fiji (archive 20261013-1517, ImageJ 1.54p, bundled Zulu JDK 21)
python3 script/setup-fiji-docs.py       # local imagej.net documentation for Penny's knowledge tools (optional)
python3 -I script/setup-fiji-samples.py # demo images for the Fiji demo (optional, 65 MB)
python3 -I fidelis-app/tools/fetch_junit.py   # once: the pinned JUnit runner for the Fidelis tests
```

Voice needs AWS credits, never cash: read [FUNDING.md](docs/FUNDING.md), then `npm run enable:text` and
`npm run enable:speech -- --authorized-aws-speech` ([AWS-TEXT.md](docs/AWS-TEXT.md), [AWS-SPEECH.md](docs/AWS-SPEECH.md)).
Without them every local tool, the MCP server and the Fidelis workstation still work.

### Windows x64: the Fidelis workstation and Penny's gateway

`script/setup.py` and `script/setup-fiji.py` refuse to run anywhere but Apple-silicon macOS, and the launch scripts
(`script/run-*.sh`, `script/build_and_run.sh`) and the Swift companion are macOS only. Everything else runs on Windows
(verified 18 October 2026 on Windows 11 with the pinned Fiji):

1. Extract the pinned Fiji Windows archive (`external/fiji.lock.json`: 20261013-1517, ImageJ 1.54p, bundled Zulu JDK 21)
   **outside** the repository and set the user environment variable `FIDELIS_FIJI_DIR` to that folder. The build uses
   Fiji's bundled JDK: no Maven, no system-wide Java, nothing inside the Fiji folder is modified.
2. Python 3.11+ (`py -3.11 -I …` when the default `python` is older), Node.js 22+ and Git (Git Bash for the shell
   scripts and for `npm test`, whose script sets an environment variable POSIX-style).

```powershell
py -3.11 -I fidelis-app/tools/fetch_junit.py              # once: the pinned JUnit runner
py -3.11 -I verification/fetch_fixtures.py --only FX-003  # Blobs; the bridge and journey tests skip without it
py -3.11 -I fidelis-app/tools/build.py                    # javac/jar with Fiji's JDK
py -3.11 -I fidelis-app/tools/run.py --isolated-home      # the workstation on its own: typed commands, local interpreter
py -3.11 -I fidelis-app/tools/test.py                     # JUnit 5 in one live Fiji JVM (473 tests, ~6 min)
```

3. Penny over Fidelis without the companion (typed requests and the instant routes; the model and voice need the AWS
   credits below): write 64 random hex characters to `.local/state/bridge-token`, then in one shell
   `set LUMA_APP=fidelis`, `set LUMA_ROOT=<checkout>`, `npm ci`, `node --import tsx packages/gateway/src/server.ts`, and
   in another `py -3.11 -I fidelis-app/tools/run.py --isolated-home --prop luma.root=<checkout> --prop fidelis.penny=true`.
   The workspace's Penny strip connects to the gateway on 127.0.0.1:49555 and the bridge listens on 49558.
4. The optional local speech worker is a Python 3.11 virtualenv from `speech/requirements.lock.txt` plus
   `speech/setup_speech.py` for the hash-pinned `tiny.en` model (`-Dfidelis.stt.python` names the interpreter). On macOS
   and Linux the same lock installs with `python3.11 -m venv .venv-stt && .venv-stt/bin/pip install --require-hashes -r
   speech/requirements.lock.txt`, then `.venv-stt/bin/python -I speech/setup_speech.py`; `WhisperSpeechService` and
   `fidelis-app/tools/test.py` default to `.venv-stt/bin/python` there, so the speech tests run.
5. Known Windows limits: the AWS-backed model and voice calls are not usable yet (the credit ledger syncs its directory
   to disk, which Windows refuses; its tests show the EPERM), and 17 of the gateway's 252 tests fail only on Windows
   (symlink creation without Developer Mode, that directory sync, path separators); they pass on macOS, where CI runs.

## Verify

```sh
npm run check                                   # gateway: TypeScript, 249 focused tests, tool-reference consistency
./script/test-fiji-bridge.sh                    # Fiji bridge: 7 contract suites in a windowless ImageJ
python3 -I fidelis-app/tools/build.py           # Fidelis: compile app and tests with Fiji's JDK
python3 -I fidelis-app/tools/test.py            # Fidelis: JUnit 5 in one live Fiji JVM (451 tests, several minutes)
python3 -I verification/test_verification.py    # verification harness self-tests
swift test --package-path apps/companion        # native companion (48 tests)
```

`npm run doctor` checks local setup and connections without changing anything; `npm run smoke` drives a real MCP client
through the running gateway, read-only. Real-application and voice checks are separate, explicitly authorized commands
described in [CONTRIBUTING.md](docs/CONTRIBUTING.md). The GitHub workflow runs the gateway and native lanes; the Fidelis
and Fiji suites need the pinned Fiji and run locally.

## What is verified

- **Fiji by voice**: "segment the cells" → 67 cells outlined on Blobs, "how many and the mean intensity" → 67 / 190 from
  the original pixels, "stricter / looser" → instant re-segmentation (~40 ms, no model round), "go back", "save this as
  a macro". A corrected-analysis conversation on three calibrated fields through the real gateway and credit-checked
  model: 12 objects → split 13 → remove under 20 µm² 10 → folder replay 3 × 10. [DEMO-FIJI.md](docs/DEMO-FIJI.md),
  [qa/PENNY-VOICE-WORKFLOW.md](docs/qa/PENNY-VOICE-WORKFLOW.md), [CAPABILITIES.md](docs/CAPABILITIES.md).
- **Fidelis**: the J-12 cell-counting journey (open → Subtract Background → Gaussian → Otsu dark → Convert to Mask →
  Watershed → Set Measurements → Measure → Analyze Particles → save) matches the pinned Fiji's own output exactly on
  four fixtures (71 particles on Blobs, 345 on BBBC001, 300 on BBBC004), through the typed engine and through the
  workspace, and the exported macro replays to the same state. [FIDELIS-VERIFICATION.md](docs/FIDELIS-VERIFICATION.md).
- **Penny × Fidelis**: the bridge's Blobs voice journey over HTTP (`segment_cells` 67 objects on a duplicate mask, the
  original's pixels untouched and holding the 67 ROIs; `measure {scope:"rois"}` mean 190.34 from the original pixels),
  36 catalogue commands walked through their original dialogs, and the workspace strip, plan card and canvas flash
  against in-test gateway and bridge servers. [INTEGRATION.md](docs/INTEGRATION.md) keeps the protocol-tested,
  real-app-tested and not-yet-verified lists apart.
- **QuPath**: a public slide fixture detected 899 cells and classified 49 above a chosen DAB threshold; the three-minute
  voice flow (region → detect 294 cells → classify → "show me the positive cells" → stricter → undo → save workflow) ran
  end to end on 17 October 2026. [DEMO.md](docs/DEMO.md), [SOFTWARE-COVERAGE.md](docs/SOFTWARE-COVERAGE.md).

Counts verify that the software did what was asked; they are not validated segmentation or biology
([DATASETS.md](docs/DATASETS.md)). Checkpoints restore image state and object hierarchies, not files or arbitrary
script effects.

## Documentation

| Start here | Purpose |
|---|---|
| [Product brief](docs/PRODUCT.md) | Problem, accepted direction and scope |
| [Capability matrix](docs/CAPABILITIES.md) | Implemented, verified and blocked behaviour |
| [Setup](docs/PENNY-SETUP.md) | Install, launch, MCP configuration and troubleshooting |
| [Architecture](docs/PENNY-ARCHITECTURE.md) · [Protocol](docs/PROTOCOL.md) | Process ownership, trust boundaries, gateway routes and approval semantics |
| [Fidelis engine](docs/FIDELIS-ENGINE.md) | Architecture invariants and the `sc.fidelis.engine.api` contract |
| [Fidelis verification](docs/FIDELIS-VERIFICATION.md) · [Fidelis demo](docs/FIDELIS-DEMO.md) | Baselines, parity results, and the J-12 runbook |
| [Integration](docs/INTEGRATION.md) · [Fidelis bridge contract](docs/FIDELIS-PROTOCOL.md) | How Penny drives Fidelis and what the bridge serves |
| [Fiji setup](docs/FIJI-SETUP.md) · [Fiji bridge contract](docs/FIJI-PROTOCOL.md) · [Fiji demo](docs/DEMO-FIJI.md) | The stand-alone Fiji profile |
| [Tool references](docs/TOOL-REFERENCE.md) ([Fiji](docs/TOOL-REFERENCE-FIJI.md), [Fidelis](docs/TOOL-REFERENCE-FIDELIS.md)) | Generated schemas and risk classes; machine-readable `docs/tool-schemas*.json` |
| [Voice](docs/VOICE.md) · [AWS speech](docs/AWS-SPEECH.md) · [AWS text](docs/AWS-TEXT.md) · [Funding](docs/FUNDING.md) | The voice route, its guards and the credit-only rule |
| [Desktop control](docs/DESKTOP-CONTROL.md) · [API lookup](docs/API-LOOKUP.md) | Accessibility actions beyond the app windows; pinned QuPath source lookup |
| [QuPath task coverage](docs/SOFTWARE-COVERAGE.md) · [Datasets](docs/DATASETS.md) | Routes with proof boundaries; data provenance |
| [Scientific agent test](docs/SCIENTIFIC-AGENT-TEST.md) · [Synthetic import test](docs/SYNTHETIC-IMPORT-TEST.md) | The separately authorized real-model and import fixtures |
| [Contributor guide](docs/CONTRIBUTING.md) · [AGENTS.md](AGENTS.md) | Validation layers, documentation expectations, rules for contributors and coding agents |
| [Build journal](docs/BUILD-JOURNAL.md) · [QA records](docs/qa/) | Dated decisions and evidence |
| Agent skills: [Fiji](skills/fiji/SKILL.md), [Fidelis](skills/fidelis/SKILL.md), [QuPath](skills/qupath/SKILL.md) | Scientific operating guidance the model reads |
| [Pitch material](docs/pitch/README.md) | Decks and evidence receipts |

## Repository map

```text
packages/gateway/      TypeScript gateway: tool validation, decisions and audit, MCP, voice and text transport, app profiles
apps/companion/        SwiftPM native macOS companion: floating bar, pointer, audio, desktop control
adapters/fiji/         Java bridge plugin for the stand-alone Fiji profile (port 49557)
adapters/qupath/       Java 25 QuPath extension (port 49556)
fidelis-app/           The Fidelis workstation: src/main/java/sc/fidelis/{engine,ui,nl,speech,penny}, tests, tools/ (build, run, test)
verification/          Reference runner, J-12 baselines from the pinned Fiji, comparators, replay and GUI smoke
fixtures/              Fixture manifest (ImageJ samples, BBBC images; downloads are git-ignored) and the synthetic fixtures
speech/                Fidelis's isolated local speech worker (faster-whisper) and its hash-locked requirements
external/              Fiji runtime and speech-model pins (downloads are git-ignored)
skills/                Authored operating guidance for Fiji, Fidelis and QuPath
script/                Setup, build, launch, smoke and guarded fixture checks
docs/                  Documentation, generated tool references, command catalogue (docs/data), screenshots, pitch
third_party/           Preserved upstream licence texts and package inventories
```

## Licence and credits

Original code and documentation are licensed under the **Apache License 2.0** ([LICENSE](LICENSE)); the scope is
defined in [NOTICE](NOTICE). Apache 2.0 does not apply to Fiji, ImageJ, QuPath, the JDK or any other upstream software:
none is included in this repository, each is used as a separately downloaded, unmodified installation and keeps its own
licence ([third_party/NOTICE.md](third_party/NOTICE.md)). Public sample imagery has its own provenance in
[DATASETS.md](docs/DATASETS.md).

Fidelis builds on [Fiji](https://imagej.net/software/fiji/) / [ImageJ](https://imagej.net) (ImageJ 1.x, ImageJ2,
SciJava, ImgLib2, SCIFIO, Bio-Formats), [faster-whisper](https://github.com/SYSTRAN/faster-whisper) and OpenAI
[Whisper](https://github.com/openai/whisper); Penny builds on [QuPath](https://qupath.github.io). Key references:
Schindelin et al. (2012), [doi:10.1038/nmeth.2019](https://doi.org/10.1038/nmeth.2019); Rueden et al. (2017),
[doi:10.1186/s12859-017-1934-z](https://doi.org/10.1186/s12859-017-1934-z); Bankhead et al. (2017),
[doi:10.1038/s41598-017-17204-5](https://doi.org/10.1038/s41598-017-17204-5). This is an independent project, not
endorsed by the Fiji, ImageJ or QuPath developers.

Repositories: [`Sleep-On-It/Voice`](https://github.com/Sleep-On-It/Voice) is the primary repository (`origin`);
[`AyushGupta05/Voice`](https://github.com/AyushGupta05/Voice) (`publish`) is the fork where Penny was built and is kept
in sync. The Fidelis history was merged here history-preserving from
[`Sleep-On-It/Fidelis`](https://github.com/Sleep-On-It/Fidelis).

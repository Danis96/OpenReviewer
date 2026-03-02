<p align="center">
  <img src="docs/openreviewr-logo.png" alt="OpenReviewr logo" width="520" />
</p>

# Open Reviewer

Open Reviewer is an IntelliJ Platform plugin that helps teams review mobile codebases faster by combining:

- startup risk analysis (deterministic + AI-calibrated),
- architecture fast scan and graph visualization,
- code-tour indexing and AI summaries,
- repository commit-checklist enforcement.

The plugin is implemented in Kotlin and runs inside IntelliJ IDEA-based IDEs.

## What This Plugin Does

Open Reviewer adds a tool window with four tabs:

1. `Startup Risk`
- Detects startup entry points (Android/Flutter and related patterns).
- Scans startup call paths for risky patterns (blocking calls, sync I/O, network at startup, heavy initialization).
- Calculates deterministic risk score (`0..100`) with severity thresholds.
- Generates AI suggestions and AI risk calibration.
- Anchors suggestions to files/lines and decorates editors with gutter hints.

2. `Architecture`
- Runs fast heuristic architecture scan.
- Shows guessed architecture patterns and confidence.
- Builds evidence tables from code signals.
- Renders dependency graph (JCEF + Cytoscape, fallback text mode when JCEF is unavailable).
- Supports filtering and node inspection.

3. `Tours`
- Scans project comments for tour markers (`@tour` and legacy aliases).
- Indexes detected stops and updates on VFS changes.
- Lets users analyze selected/all stops with AI onboarding summaries.
- Includes tour playback controls and quick open-in-editor actions.

4. `Repo Setup`
- Detects VCS platform/repo setup.
- Installs and validates commit checklist spec.
- Helps generate/update PR/MR templates.
- Works with commit and pre-push gates.

## Core Features

- IntelliJ tool window integration via `OpenReviewerToolWindowFactory`.
- Project-level startup analysis service: `StartupAnalysisService`.
- Risk scoring engine: `RiskScoringEngine`.
- AI client abstraction with provider support:
  - `OPENAI`
  - `GOOGLE` (OpenAI-compatible Gemini endpoint)
  - `COHERE`
  - `HUGGINGFACE`
  - `CUSTOM` (OpenAI-compatible endpoint)
- Commit checklist enforcement at:
  - commit time (`CheckinHandlerFactory`)
  - push time (`PrePushHandler`)
- Settings UI under `Settings > Tools > Open Reviewer`.

## Project Stack

- Kotlin JVM `2.1.0`
- Java `21`
- IntelliJ Platform Gradle Plugin `2.7.1`
- IntelliJ target: `IC 2025.1.4.1` (`sinceBuild=251`)
- Test framework: IntelliJ Platform test framework + JUnit4
- Linting: ktlint Gradle plugin

## Prerequisites

- JDK 21 available on your machine
- Gradle wrapper (already in repo)
- IntelliJ IDEA (for plugin development)

## Quick Start

### 1) Build

```bash
./gradlew build
```

### 2) Run tests

```bash
./gradlew test
```

### 3) Run lint

```bash
./gradlew ktlintCheck
```

### 4) Launch sandbox IDE with plugin

```bash
./gradlew runIde
```

Note: `build.gradle.kts` currently passes a specific local project path to `runIde`:

```kotlin
runIde {
    args("/Users/danispreldzic/Desktop/Danis/PROJECTS/whiskr_nutrition_ai")
}
```

Update that path for your own environment if needed.

## Convenience Scripts

The repo includes helper scripts:

```bash
./scripts/test.sh
./scripts/ktlint-check.sh
./scripts/ktlint-format.sh
```

## Plugin Configuration

- Plugin ID: `com.example.open.reviewer.OpenReviewer`
- Plugin name: `Open Reviewer`
- Tool window ID: `Open Reviewer`
- Main config file: `src/main/resources/META-INF/plugin.xml`

## AI Settings

Configure AI in:

`Settings > Tools > Open Reviewer`

Available settings:

- Provider
- API key
- Model
- Endpoint (for `CUSTOM`)
- `Include code context in AI requests`

Connection checks are provider-aware and run via lightweight HTTP ping.

## Commit Checklist System

Open Reviewer supports repository policy enforcement using `COMMIT_CHECKLIST.md`.

### Spec discovery

Canonical locations (first existing path is used):

1. `.openreviewer/COMMIT_CHECKLIST.md`
2. `COMMIT_CHECKLIST.md`

### Spec format

Sections parsed:

- `## Description`
- `## Type of Change`
- `## Checklist`
- `## Reviewer’s Guidance`

Example:

```markdown
<!-- openreviewer:version=1 -->

## Description
Describe what changed and why.

## Type of Change
- Feature
- Fix
- Refactor

## Checklist
- [ ] Tests updated
- [ ] Backward compatibility verified

## Reviewer’s Guidance
Pay attention to migration and rollback safety.
```

### Commit/push gating behavior

For complete metadata, commit messages must include an Open Reviewer block with required fields:

- `type`
- `checklist`
- `description` (only when configured as required)

Tag format expected by parser:

```text
[openreviewer]
...
[/openreviewer]
```

If metadata is missing, behavior depends on enforcement mode:

- `BLOCK`: prevent commit/push until completed
- `WARN`: allow override after confirmation dialog

## Tours Markers

Accepted tour markers in comments:

- `@tour`
- `@tour: optional description`
- legacy aliases:
  - `@OpenReviewrTour`
  - `@GenieTour`

## Repository Layout

```text
src/main/kotlin/com/example/open/reviewer/
  analysis/           # startup analysis + scoring
  ai/                 # provider clients + suggestion logic
  architecture/       # fast scan, pipeline, graph payloads
  commitchecklist/    # spec parsing, setup, commit/push gates
  editor/             # gutter decorations
  settings/           # persistent settings + configurable UI
  toolwindow/         # tabbed tool window composition
  tour/               # marker scanning, index, player, AI summaries

src/main/resources/
  META-INF/plugin.xml
  graph/              # graph renderer assets (Cytoscape)

src/test/kotlin/com/example/open/reviewer/
  ...                 # unit and acceptance tests
```

## Additional Docs

- `LOGIC_README.md` - startup risk and AI suggestion pipeline details
- `TOUR_LOGIC_README.md` - tours architecture and UX flow
- `TESTING.md` - test commands and notes
- `CHANGELOG.md` - release notes

## Branding Asset

- Logo path used by this README: `docs/openreviewr-logo.png`

## Testing Scope

Tests currently cover:

- startup risk scoring/scanning
- tours marker parsing and context logic
- commit checklist parser, validation, setup, VCS detection
- architecture scanners, normalizers, analysis pipeline, cache behavior

Run specific tests:

```bash
./gradlew test --tests "com.example.open.reviewer.analysis.RiskScoringEngineTest"
./gradlew test --tests "com.example.open.reviewer.analysis.scanner.StartupRiskScannerTest"
```

## Troubleshooting

- `AI connection fails`
  - Verify API key.
  - Verify provider/model combination.
  - For `CUSTOM`, verify endpoint is OpenAI-chat-compatible.
- `No tour stops found`
  - Ensure markers are in comments and match accepted tag formats.
  - Trigger `Refresh` in Tours tab.
- `Graph not interactive`
  - JCEF may be unavailable; plugin falls back to text graph view.
- `runIde opens wrong project`
  - Update the hardcoded `runIde.args(...)` path in `build.gradle.kts`.

## Development Notes

- Keep Java/Kotlin target at 21 unless upgrading platform compatibility.
- Prefer deterministic analysis as baseline; AI is additive and failure-tolerant.
- Commit checklist logic is designed to keep local workflows functional even when spec parsing fails (graceful fallback).

## License

No license file is currently present in this repository. Add one before publishing/distributing externally.

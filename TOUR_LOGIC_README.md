# OpenReviewr Tours Logic (Sprint 1 + Sprint 2.5)

## Overview
OpenReviewr Tours provides onboarding walkthroughs by letting developers mark important code locations with comments, scanning the project for those markers, listing tour stops in the IDE, and generating AI summaries for selected/all stops.

The feature is integrated into the existing `Open Reviewer` tool window as a `Tours` tab.

## Marker Format
Supported markers:
- `// @tour: optional description`
- `// @tour`
- Legacy support: `// @OpenReviewrTour: ...`
- Legacy support: `// @GenieTour: ...`

Rules implemented:
- Marker must start with `@tour` (legacy `@OpenReviewrTour` and `@GenieTour` are also accepted).
- Description is optional.
- Multiple markers per file are supported.

Reference:
- `src/main/kotlin/com/example/open/reviewer/tour/scanner/OpenReviewrTourMarkerParser.kt`

## Supported Mobile Project Detection
Detection signals:
- Android: `AndroidManifest.xml`
- Flutter: `pubspec.yaml`
- React Native: `package.json` + `react-native` dependency signal
- iOS: `.xcodeproj` or `Info.plist`

Reference:
- `src/main/kotlin/com/example/open/reviewer/tour/detection/MobileProjectDetector.kt`

## Data Model
Core models:
- `MobilePlatform`
- `OpenReviewrTourStop(filePath, lineNumber, description, platform)`
- `OpenReviewrTourSummary(summary, keyResponsibilities, risks, relatedFiles)`
- `OpenReviewrTourAnalysisResult(stop, summary?, error?)`

References:
- `src/main/kotlin/com/example/open/reviewer/tour/model/OpenReviewrTourModels.kt`
- `src/main/kotlin/com/example/open/reviewer/tour/analysis/OpenReviewrTourSummaryModels.kt`

## Scan and Index Architecture (Sprint 1)
### Scanner
`OpenReviewrTourScanner` uses PSI + VFS traversal to:
1. Iterate project content files.
2. Skip excluded/generated/vendor paths and oversized files.
3. Parse PSI comments for markers.
4. Resolve line numbers from document offsets.
5. Infer platform by file extension.
6. Return sorted tour stops.

Reference:
- `src/main/kotlin/com/example/open/reviewer/tour/scanner/OpenReviewrTourScanner.kt`

### Index Service
`OpenReviewrTourIndexService` is a project service that:
- Stores in-memory stops and detected platforms.
- Publishes snapshots over message bus (`TOPIC`).
- Rebuilds on startup.
- Rebuilds on VFS changes with debounce.
- Scans in cancellable background task (non-blocking).

References:
- `src/main/kotlin/com/example/open/reviewer/tour/index/OpenReviewrTourIndexService.kt`
- `src/main/kotlin/com/example/open/reviewer/tour/index/OpenReviewrTourStartupActivity.kt`
- `src/main/resources/META-INF/plugin.xml`

## Tool Window Integration
`OpenReviewerToolWindowFactory` now creates tabbed content:
- `Startup Risk` (existing)
- `Tours` (new)

Reference:
- `src/main/kotlin/com/example/open/reviewer/toolwindow/OpenReviewerToolWindowFactory.kt`

## Tours UI (Sprint 1 + Sprint 2.5)
`OpenReviewrToursPanel` provides:
- Tour stops list with file, description, line, platform badge.
- Actions: `Refresh`, `Analyze Selected`, `Analyze All`.
- Status text: scanning/analyzing/unsupported/count.
- Click-to-open navigation:
  - Double-click a tour stop to open file at marker line.
  - Press `Enter` on a selected stop to open file at marker line.
- Details pane sections:
  - Summary
  - Key Responsibilities
  - Risks
  - Related Files
  - Last analyzed timestamp

Reference:
- `src/main/kotlin/com/example/open/reviewer/tour/ui/OpenReviewrToursPanel.kt`

## AI Analysis Pipeline (Sprint 2)
### Service
`OpenReviewrTourAnalysisService` handles a single stop analysis:
1. Validate AI settings (API key, custom endpoint).
2. Skip generated/vendor file paths.
3. Build trimmed code context.
4. Build structured prompt for onboarding output.
5. Call existing AI client abstraction (`AiClientService`).
6. Parse JSON response into `OpenReviewrTourSummary`.
7. Return success/error result object.

Reference:
- `src/main/kotlin/com/example/open/reviewer/tour/analysis/OpenReviewrTourAnalysisService.kt`

### Context Strategy
`OpenReviewrTourContextBuilder`:
- If file <= soft cap (1000 lines): send full file.
- Else: send marker-centered window (1000 lines).
- Hard cap safety: 1200 lines max.
- Context is line-numbered for better grounding.

Reference:
- `src/main/kotlin/com/example/open/reviewer/tour/analysis/OpenReviewrTourContextBuilder.kt`

## Analyze Selected / Analyze All Flow
From UI:
1. User clicks Analyze action.
2. Background task starts (cancellable, progress updates).
3. For each stop, service runs AI pipeline.
4. Result cached in-memory keyed by stop identity.
5. UI details pane renders latest result/error.

Reference:
- `src/main/kotlin/com/example/open/reviewer/tour/ui/OpenReviewrToursPanel.kt`

## Error and Edge Case Handling
Implemented handling for:
- Missing API key.
- Missing CUSTOM endpoint.
- Unsupported project detection.
- Empty/unreadable source file.
- Generated/vendor files (skip analysis).
- Invalid/non-JSON AI response.
- No selected stop / no stops in list.
- Cancellation-safe background execution.

## Performance Considerations
Implemented:
- Non-blocking background scan and analysis tasks.
- Debounced VFS-triggered rescans.
- Cancellable progress tasks.
- In-memory index and summary cache.
- Source filtering by extension/path/size.

## Tests Added
- Marker parser tests.
- Mobile platform detector tests.
- Context trimming tests.

References:
- `src/test/kotlin/com/example/open/reviewer/tour/scanner/OpenReviewrTourMarkerParserTest.kt`
- `src/test/kotlin/com/example/open/reviewer/tour/detection/MobileProjectDetectorTest.kt`
- `src/test/kotlin/com/example/open/reviewer/tour/analysis/OpenReviewrTourContextBuilderTest.kt`

## Validation Status
Latest local verification completed:
- `./gradlew test` passed.
- `./gradlew ktlintCheck` passed.

## Summary Cache and Timestamps (Sprint 2.5)
For each analyzed stop, the panel now caches:
- Parsed summary object.
- `analyzedAt` timestamp (milliseconds).

The details pane renders `Last analyzed: yyyy-MM-dd HH:mm:ss` using local system timezone.

This cache remains in-memory for the IDE session (no persistent disk cache yet).

## Current Scope Boundary
Implemented now:
- Full Sprint 1 (scan/index/list UI).
- Full Sprint 2 (AI analysis + rendering).
- Sprint 2.5 polish:
  - Clickable tour stops open at marker line.
  - Cached timestamps shown per summary.

Not implemented yet:
- Persistent summary cache across IDE restarts.
- Streaming AI responses.
- Click-to-open file/line navigation from list.
- Team-shared tours / guided multi-step tour flows.

# Open Reviewer Logic README

This document explains the core runtime logic in Open Reviewer: how startup risks are detected, how scores are calculated, how AI suggestions are produced, and how they are mapped to code lines and gutter icons.

## 1. End-to-End Flow

1. User clicks **Analyze Startup** in the tool window.
2. `OpenReviewerToolWindowContent` runs `StartupAnalysisService.analyzeStartup(...)` in a background task.
3. Analysis service:
   - Detects likely startup entry points.
   - Runs startup analyzers (currently heuristic analyzer).
   - Collects findings.
   - Computes deterministic risk score.
   - Requests AI suggestions.
   - Requests AI risk adjustment.
   - Merges final score and returns `StartupAnalysisResult`.
4. Tool window updates:
   - Risk score UI and AI summary cards.
   - Suggestions list.
   - Editor gutter suggestion icons and hover tooltips.

## 2. Entry Point Detection

Implemented in `StartupEntryPointDetector`.

### Candidate file selection
- Walks the project tree recursively.
- Skips ignored directories (for example `.git`, `build`, `node_modules`, `test`, `tests`, `integration_test`).
- Stops after a scan safety limit (`scanLimit = 6000` files).
- Keeps files that look startup-related by filename, such as:
  - `main.dart`, `main.kt`, `main.java`
  - `application.kt`, `application.java`
  - names containing `mainactivity`, `startup`, `application`

### Entry signatures
For each candidate file, regexes detect startup functions like:
- Kotlin/Java: `main`, `onCreate`
- Dart: `main` / `Future<void> main`
- Swift: `application`, `didFinishLaunching`

Each match becomes `DiscoveredEntryPoint(name, file, line)`.

## 3. Risk Scanning (Heuristic)

Implemented by `HeuristicStartupAnalyzer` + `StartupRiskScanner`.

### Call-tree traversal
- Builds a function index from code files around entry points.
- Seeds queue with discovered entry functions.
- Breadth-first traverses function calls up to `maxDepth = 2`.
- Resolves calls first in same file, then by function name globally.
- Deduplicates visited nodes by `(file, function, line, depth)`.

### Pattern-based findings
Scanner emits `Finding` objects with title, description, severity, and location (`filePath`, `line`, snippet).

Patterns currently include:
- **Synchronous I/O** (WARN)
  - file APIs
  - `SharedPreferences.commit()`
  - direct DB open patterns (`SQLiteDatabase.open`, `Room.databaseBuilder`, `openDatabase`)
- **Network usage** in startup path (WARN)
  - `HttpClient`, `Retrofit`, `OkHttpClient`, `URLSession`, etc.
- **Blocking calls** (CRITICAL)
  - `Thread.sleep`, `sleep`, `runBlocking`
- **Heavy initialization** (WARN, only depth 0 entry node)
  - at least 3 loops OR at least 8 instantiations in startup entry function

Final findings are deduplicated by `(title, filePath, line, snippet)`.

## 4. Deterministic Risk Score Calculation

Implemented in `RiskScoringEngine`.

### Default severity weights
- INFO = 10
- WARN = 25
- CRITICAL = 45

### Formula
`rawScore = sum(weight(severity) for each finding)`

`riskScore = clamp(rawScore, 0, 100)`

### Risk level thresholds
- LOW: `< 35`
- MEDIUM: `35..69`
- HIGH: `>= 70`

These values come from `RiskScoringConfig.DEFAULT` and are centralized/configurable.

## 5. AI Suggestion Generation

Implemented in `AiSuggestionService.generateSuggestions(...)`.

### Configuration gate
AI suggestions fall back to deterministic suggestions if:
- API key is missing, or
- provider is `CUSTOM` and endpoint is missing, or
- provider call/parsing fails.

### Prompt context
Prompt includes:
- platform heuristic (Android/Flutter)
- entry point summary
- top findings (up to 5)
- optional bounded code context if enabled (`includeCodeContext`)

### Expected JSON schema
The service asks model to return array entries with:
- `title`
- `impact` (`LOW|MEDIUM|HIGH`)
- `reasoning`
- `file_path` (optional)
- `line` (optional)
- `code_snippet` (optional)

### Parsing strategy
- Strips markdown/code-fence/thinking wrappers.
- Tries JSON-like parsing first.
- Then markdown-like key parsing (`Title`, `Impact`, `Reasoning`).
- Then paragraph salvage fallback.

If all parsing fails, deterministic fallback suggestions are used.

## 6. Suggestion Anchoring to Code Lines

Open Reviewer requires per-line anchors for editor gutter icons.

### Anchor sources
1. Direct AI anchors (`file_path` + `line`) if present.
2. Otherwise, service attempts **best finding match**:
   - token overlap between suggestion text and finding text/snippet
   - small severity boost (CRITICAL > WARN > INFO)
   - copies matched finding location into suggestion

This happens in `StartupAnalysisService.anchorSuggestions(...)`.

## 7. AI Risk Adjustment (Score Calibration)

Implemented in `AiSuggestionService.generateRiskAdjustment(...)`.

### Purpose
Deterministic score is stable and explainable. AI can optionally calibrate that score based on richer context.

### AI output format
Expected JSON includes:
- `risk_score` (0..100, optional)
- `confidence` (0.0..1.0)
- `reason`
- `risk_adjustments[]` with:
  - `factor`
  - `delta` (-10..10 each)
  - `confidence` (0.0..1.0)
  - `evidence`

### Merge logic
- If explicit `risk_score` exists: use it (bounded 0..100).
- Else derive score from weighted adjustments:
  - `weightedDelta = sum(delta * confidence)`
  - `effectiveConfidence = overallConfidence or avg(adjustment.confidence) or 0`
  - `derivedDelta = round(weightedDelta * effectiveConfidence)` bounded to `[-20, 20]`
  - `modelRiskScore = clamp(baseRiskScore + derivedDelta, 0, 100)`

`StartupAnalysisService` then converts this final value to `RiskLevel` through `RiskScoringEngine.scoreFromValue(...)`.

## 8. UI Binding and Editor Gutter Icons

### Tool window
`OpenReviewerToolWindowContent.applyAnalysisResult(...)` updates:
- progress/risk UI
- AI summary cards
- suggestions list

### Editor decorations
`EditorSuggestionDecorationService`:
- clears old markers
- filters to **anchored suggestions only** (`filePath` + valid `line`)
- finds open text editors for each file
- adds line highlighters with gutter icons
- icon by impact:
  - LOW -> info icon
  - MEDIUM -> warning icon
  - HIGH -> error icon
- tooltip on hover shows **title + details** (HTML formatted)

On analysis failure, gutter markers are cleared.

## 9. Fallback / Safety Behavior

- No entry points: no findings, score remains low, suggestions can still be fallback text.
- Missing AI config or API errors: deterministic flow still works.
- Parser robustness: multiple parsing layers to prevent empty result.
- Score always clamped `0..100`.
- Scanner ignores very large files (`> 600_000` bytes) and handles read failures safely.

## 10. Where to Change Logic Quickly

- Scoring weights/thresholds: `RiskScoringConfig.DEFAULT` in `RiskScoringEngine.kt`
- New detection patterns: `StartupRiskScanner` pattern lists
- Traversal depth: `StartupRiskScanner(maxDepth = 2)` in `HeuristicStartupAnalyzer`
- AI prompt/schema: `AiSuggestionService.buildPrompt(...)`
- AI calibration behavior: `AiSuggestionService.parseRiskAdjustmentPayload(...)`
- Gutter icon/tooltip behavior: `EditorSuggestionDecorationService`


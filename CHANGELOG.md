# Changelog

## Unreleased

### Added
- Initial Open Reviewer tool window scaffold with:
  - Analyze Startup and Configure API actions
  - Performance Risk Score section
  - AI Suggestions section
- Background startup analysis execution with IntelliJ `Task.Backgroundable`.
- Project service-based analysis pipeline (`StartupAnalysisService`).
- Deterministic risk scoring via `RiskScoringEngine`.
- Configurable scoring thresholds and severity weights in one place.
- Heuristic startup scanner pipeline:
  - Startup entry-point detection
  - Depth-limited call-tree traversal
  - Risk pattern detection for blocking calls, synchronous I/O, network hints, and heavy initialization.
- Optional code snippets on findings.
- Notification group and failure notifications for analysis errors.
- Settings page under `Settings -> Tools -> Open Reviewer` with persisted provider/API/model/endpoint configuration.
- App-level settings persistence using `PersistentStateComponent` (`open-reviewer.xml`).
- `Test Connection` flow with background ping and IDE notifications.
- `AiClient` suggestion generation contract with stub implementation.
- `AiSuggestionService` with:
  - safe/tight prompt construction (platform, entry-point summary, top findings, short snippets)
  - fallback suggestions when provider credentials are unavailable
  - response parsing into structured suggestions (`title`, `details`, `impact`).
- Minimal automated test coverage:
  - `RiskScoringEngine` unit tests
  - lightweight startup scanner pattern detection tests with tiny fixtures.
- Test run documentation in `TESTING.md`.
- Provider-aware API connectivity checks (`OPENAI`, `COHERE`, `HUGGINGFACE`, `CUSTOM`) via lightweight HTTP ping.

### Changed
- Finding severities standardized to `INFO`, `WARN`, and `CRITICAL`.
- Tool window findings renderer now includes snippet context when available.
- Startup analysis result now includes structured AI suggestions.
- Tool window AI Suggestions section now renders suggestion content from analysis output.
- `Configure API` button now opens Open Reviewer settings directly.
- AI connection test now performs provider-aware HTTP ping checks (including Hugging Face token validation endpoint).
- Build/test tooling updated to run fast JUnit4-based unit tests in IntelliJ platform test runtime.
- Open Reviewer settings UI redesigned to match the target layout more closely (provider, masked API key with reveal toggle, model field, test button, note panel).
- Settings screen now shows explicit inline connection states:
  - loading (`Testing connection`)
  - success (`Connection successful`)
  - failure (`Connection failed`)
- Fixed clipped/truncated connection state rendering by removing global row-height forcing and applying explicit sizing only to input controls.
- Refined connection state card visuals to a more elegant style with subtle neutral background, soft border, and compact colored accent instead of heavy full-surface fills.

# Testing

## Quick scripts

```bash
./scripts/ktlint-check.sh
./scripts/ktlint-format.sh
./scripts/test.sh
```

## Run all tests

```bash
./gradlew test
```

## Run specific test classes

```bash
./gradlew test --tests "com.example.open.reviewer.analysis.RiskScoringEngineTest"
./gradlew test --tests "com.example.open.reviewer.analysis.scanner.StartupRiskScannerTest"
```

## Notes

- Tests are designed to be fast and deterministic.
- Scanner tests use tiny temporary code fixtures and do not require network access.

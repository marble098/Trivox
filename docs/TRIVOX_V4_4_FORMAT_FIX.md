# Trivox v4.4 — localized progress format and CI diagnostics fix

## Failure reproduced

Staged run `30859946781` compiled the Debug and Release variants and passed
both unit-test variants. Android Lint then reported four `StringFormatMatches`
errors. The four entries were duplicates of the same contract mismatch around
`subscription_progress_format`: the resource declared integer conversions while
Lint resolved the first call-site argument as a string in the localized call.

## Correction

- `subscription_progress_format` now has one unambiguous contract in every
  language: `%1$s`, `%2$s`, `%3$s`.
- Both Kotlin call sites explicitly convert the two counts to strings.
- No resource is marked `formatted="false"`.
- No Lint baseline, issue suppression, or `abortOnError=false` is used.
- `tools/validate-string-formats.py` compares formatter signatures in every
  `values*/strings.xml` file and enforces the progress-format contract before
  Gradle runs.
- The obsolete Node.js 20 artifact uploader was moved to
  `actions/upload-artifact@v7`.
- Deprecated memory-trim symbolic constants were replaced by private stable
  threshold constants, preserving behavior without compiler deprecation noise.

## Safety

The installer can reuse candidate
`bdb873af82fc939c8951df06a9d2323fa44f6f18` when it is still present locally.
It verifies that the reviewed main commit is an ancestor and that the expected
v4.3 markers exist. Otherwise it reapplies all complete v4.4 source files to the
reviewed main tree.

Only a staged GitHub Actions success can fast-forward `main`.

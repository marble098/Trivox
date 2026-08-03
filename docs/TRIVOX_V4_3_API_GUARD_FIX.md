# Trivox v4.3 Android API guard correction

The v4.2 candidate passed repository/resource validation but failed before
Gradle because the workflow searched for the literal text
`info: ApplicationExitInfo`. That pattern also matched a safe lambda inside the
runtime-gated Android 11 method, so the guard rejected the code it was meant to
protect.

v4.3 fixes both layers:

- `collectHistoricalExitReasons()` performs the runtime Android 11 check.
- `collectHistoricalExitReasonsApi30()` has an explicit `TargetApi(R)` boundary.
- `isActionableExit()` accepts only `Int` and `String`, so API-30 objects never
  escape into an unguarded helper.
- the typed `ApplicationExitInfo` lambda is removed.
- `tools/validate-api-guards.py` checks the structure semantically.
- GitHub Actions compiles and executes that validator before Gradle.
- no lint baseline, `abortOnError=false`, or broad suppression was introduced.

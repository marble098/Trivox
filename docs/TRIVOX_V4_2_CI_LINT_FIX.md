# Trivox v4.2 CI and lint correction

The v4.1 staged candidate compiled in debug and release modes and passed all
unit tests. Android Lint then rejected seven API-level references originating
from a helper that accepted `ApplicationExitInfo` outside the method whose
Android 11 runtime guard Lint could prove.

v4.2 fixes the source instead of adding a lint baseline or suppression:

- `ApplicationExitInfo` properties are read only inside the guarded Android 11
  code path.
- The filtering helper accepts plain `reason` and `description` values.
- Expected diagnostic cancellations remain excluded from crash history.
- Compiler warnings introduced by v4.1 are cleaned where practical.
- Failed staged runs persist their logs and upload lint reports for inspection.

`main` is updated only after staged `test lint` and the APK build succeed.

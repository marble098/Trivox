# Trivox Subscription, NordVPN, Diagnostics, UI and Ping Review v4.1

Reviewed base: `3911c71f4e3eecf0b93ce1253be9d432e7ae40b7`.

## Runtime evidence

The 0.2.42 diagnostics showed two implementation problems:

1. A syntactically usable HTTPS subscription URL was rejected because the
   validator required `URI.host` and rejected every fragment instead of
   normalizing the URL.
2. Closing or recreating `SubscriptionManagementActivity` called
   `shutdownNow()`. The expected interruption from the NordVPN completion
   queue was then stored as a JVM throwable and source failure.

These are lifecycle and input-normalization defects, not Xray crashes.

## Subscription URL pipeline

The URL importer now:

- trims a UTF-8 BOM and surrounding whitespace;
- adds `https://` when a user pastes a host/path without a scheme;
- requires HTTPS and rejects embedded credentials;
- converts internationalized hostnames to IDNA ASCII;
- safely removes URI fragments, because fragments are not transmitted in
  HTTP requests;
- preserves query strings;
- detects redirect loops;
- rejects HTTPS-to-non-HTTPS redirects;
- supports bounded gzip and deflate responses;
- caps decompressed response and error body sizes;
- retries temporary I/O, HTTP 408, 425, 429 and common 5xx failures;
- honours a bounded numeric `Retry-After`;
- checks thread interruption during reads, retries and redirect handling.

## Cancellation semantics

`SubscriptionCancelledException` and `isSubscriptionCancellation()` separate
expected lifecycle cancellation from provider failure. Cancellation:

- does not update `lastError`;
- does not increment the failed-source counter;
- does not produce a crash stack in Diagnostics;
- preserves the thread interrupt flag;
- cancels outstanding NordVPN country futures.

## NordVPN catalogue

The importer continues to select the best online NordLynx server for every
distinct location returned by each country query. v4.1 additionally:

- returns a structured catalogue result with `complete` and `warnings`;
- preserves previous working locations when only part of the provider
  catalogue succeeds;
- increases bounded country concurrency while reducing per-request and global
  deadlines;
- propagates cancellation through country workers and the trusted DoH
  bootstrap;
- immediately stops retry loops when interrupted;
- keeps deterministic location IDs, favorites and prior latency state.

A complete refresh replaces the source. A partial refresh merges new profiles
and retains older profiles that could not be revalidated during that attempt.

## Diagnostics policy

Routine breadcrumbs are queued on a single bounded daemon writer instead of
performing file I/O on caller threads. Diagnostics now:

- ignores expected interruption/cancellation;
- de-duplicates identical throwables in a one-minute window;
- limits stored stack size;
- records normal native begin/completed checkpoints only at debug level;
- keeps failed native checkpoints as warnings;
- filters package updates, user stops and known MIUI cleaner exits;
- flushes queued runtime entries before report generation or clearing;
- retains crashes, ANRs, native crashes, low-memory exits and unknown
  actionable exits.

## Main and subscription UI

- The three main action buttons have identical width and height.
- Connection state text becomes green only for `CONNECTED`, blue while
  preparing/connecting/reconnecting, and red on error.
- The first connection tap immediately displays a preparing state and disables
  duplicate taps while the service starts.
- The main subscription refresh button displays per-source progress.
- The subscription screen includes source/configuration counts, type and
  status badges, compact equal action buttons, and partial-update warnings.

## Ping and real-delay

- Batch Xray real-delay uses an adaptive fast ranking pass: one complete-route sample, a four-second per-target budget and at most two verified targets. This removes the previous worst-case six native requests per profile.
- Single-profile real-delay retains the user-selected multi-sample attempt count and full target pool.
- Both batch buttons show completed/total progress and restore their labels after cancellation or completion.
- The suspicious-low-value recheck remains for direct TCP tests but is removed
  from full-route Xray delay, where a genuinely low result is valid.
- The existing verified connectivity target pool, median latency, MAD jitter,
  two-thirds quorum, DNS caches and final-result flush remain intact.

## Validation strategy

The installer applies changes on an isolated `audit/**` branch, runs resource,
Python, shell and repository audits, pushes the candidate, and waits for the
full GitHub Actions test/lint/APK workflow. `main` is fast-forwarded only after
the candidate workflow passes.

## v4.1 lifecycle follow-up

The coordinator owns its active Future, rejects work after close, cancels the active task on close, and suppresses completion callbacks after destruction. MainActivity also guards UI callbacks with an explicit destroyed flag and reports foreground-service launch failures immediately.

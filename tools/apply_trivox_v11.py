#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

PATCH_NAME = "Trivox v11 dual-delay/OpenSSH/export patch"


def fail(message: str) -> "NoReturn":
    raise RuntimeError(message)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"Required file is missing: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str, dry_run: bool) -> None:
    if dry_run:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count == 0:
        fail(f"Patch anchor not found: {label}")
    if count != 1:
        fail(f"Patch anchor is ambiguous ({count} matches): {label}")
    return text.replace(old, new, 1)


def patch_ping_manager(root: Path, dry_run: bool) -> None:
    path = root / "app/src/main/java/com/trivox/client/network/PingManager.kt"
    text = read(path)
    old = '''    fun batch(
        profiles: List<ConfigProfile>,
        settings: AppSettings,
        workDir: File,
        callback: (ConfigProfile, PingResult) -> Unit
    ): List<Future<*>> {
        val real = settings.pingMethod == PingMethod.XRAY_HTTP
        return submitBounded(
            profiles = profiles,
            executor = if (real) xrayExecutor else tcpExecutor,
            workers = if (real) 1 else MAX_TCP_WORKERS
        ) { profile ->
            val result = if (real) {
                realXray(
                    profile = profile,
                    settings = settings,
                    workDir = workDir,
                    attempts = BATCH_XRAY_ATTEMPTS,
                    timeoutSeconds = BATCH_XRAY_TIMEOUT_SECONDS,
                    allowSingleSample = false,
                    maxTargetsPerSample = BATCH_XRAY_MAX_TARGETS
                )
            } else {
                measure(profile, settings, workDir)
            }
            callback(profile, result)
        }
    }
'''
    new = '''    fun batch(
        profiles: List<ConfigProfile>,
        settings: AppSettings,
        workDir: File,
        callback: (ConfigProfile, PingResult) -> Unit
    ): List<Future<*>> {
        val real = settings.pingMethod == PingMethod.XRAY_HTTP
        if (real && core != null && profiles.isNotEmpty()) {
            return listOf(
                xrayExecutor.submit(Callable {
                    BatchRealDelayRunner(core).run(
                        profiles = profiles,
                        settings = settings,
                        workDir = workDir,
                        callback = callback,
                        fallback = { profile ->
                            realXray(
                                profile = profile,
                                settings = settings,
                                workDir = workDir,
                                attempts = BATCH_XRAY_ATTEMPTS,
                                timeoutSeconds = BATCH_XRAY_TIMEOUT_SECONDS,
                                allowSingleSample = false,
                                maxTargetsPerSample = BATCH_XRAY_MAX_TARGETS
                            )
                        }
                    )
                })
            )
        }

        return submitBounded(
            profiles = profiles,
            executor = tcpExecutor,
            workers = MAX_TCP_WORKERS
        ) { profile ->
            callback(profile, measure(profile, settings, workDir))
        }
    }
'''
    text = replace_once(text, old, new, "PingManager.batch")
    write(path, text, dry_run)


def patch_main_activity(root: Path, dry_run: bool) -> None:
    path = root / "app/src/main/java/com/trivox/client/ui/MainActivity.kt"
    text = read(path)

    old_labels = '''        val labels = arrayOf(
            getString(R.string.subscription_open_settings),
            getString(R.string.subscription_delete_all_profiles),
            getString(R.string.subscription_delete_without_tcp),
            getString(R.string.subscription_delete_without_real),
            getString(R.string.subscription_delete_without_both)
        )
'''
    new_labels = '''        val labels = arrayOf(
            getString(R.string.subscription_open_settings),
            getString(R.string.subscription_export_links),
            getString(R.string.subscription_delete_all_profiles),
            getString(R.string.subscription_delete_without_tcp),
            getString(R.string.subscription_delete_without_real),
            getString(R.string.subscription_delete_without_both)
        )
'''
    text = replace_once(text, old_labels, new_labels, "subscription action labels")

    old_when = '''                when (which) {
                    0 -> openSubscriptionManager(sourceId = subscriptionId)
                    1 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) { true }
                    2 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.tcpTestStatus != TestStatus.ALIVE ||
                            it.tcpLatencyMs == null
                    }
                    3 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.realTestStatus != TestStatus.ALIVE ||
                            it.realLatencyMs == null
                    }
                    4 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        (it.tcpTestStatus != TestStatus.ALIVE || it.tcpLatencyMs == null) &&
                            (it.realTestStatus != TestStatus.ALIVE || it.realLatencyMs == null)
                    }
                }
'''
    new_when = '''                when (which) {
                    0 -> openSubscriptionManager(sourceId = subscriptionId)
                    1 -> exportSubscriptionLinks(source, profiles)
                    2 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) { true }
                    3 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.tcpTestStatus != TestStatus.ALIVE ||
                            it.tcpLatencyMs == null
                    }
                    4 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        it.realTestStatus != TestStatus.ALIVE ||
                            it.realLatencyMs == null
                    }
                    5 -> confirmSubscriptionProfileCleanup(
                        source = source,
                        profiles = profiles,
                        title = labels[which]
                    ) {
                        (it.tcpTestStatus != TestStatus.ALIVE || it.tcpLatencyMs == null) &&
                            (it.realTestStatus != TestStatus.ALIVE || it.realLatencyMs == null)
                    }
                }
'''
    text = replace_once(text, old_when, new_when, "subscription action dispatch")

    export_function = '''
    private fun exportSubscriptionLinks(
        source: SubscriptionSource,
        profiles: List<ConfigProfile>
    ) {
        val links = profiles
            .asSequence()
            .map { it.raw.trim() }
            .filter(String::isNotBlank)
            .filter { value ->
                runCatching { URI(value).scheme }
                    .getOrNull()
                    ?.lowercase(Locale.ROOT) in SHARE_SCHEMES
            }
            .distinct()
            .toList()

        if (links.isEmpty()) {
            toast(getString(R.string.subscription_export_none))
            return
        }

        val payload = links.joinToString("\\n")
        val title = getString(
            R.string.subscription_export_title,
            source.name
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, title)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, payload)
                },
                title
            )
        )
    }

'''
    anchor = '''    private fun confirmSubscriptionProfileCleanup(
'''
    if export_function.strip() not in text:
        if anchor not in text:
            fail("Patch anchor not found: confirmSubscriptionProfileCleanup")
        text = text.replace(anchor, export_function + anchor, 1)

    old_fastest = '''    private fun selectFastestProfile() {
        val fastest =
            repository.all()
                .filter {
                    it.enabled &&
                        it.tcpTestStatus ==
                        TestStatus.ALIVE &&
                        it.tcpLatencyMs !=
                        null
                }
                .minByOrNull {
                    it.tcpLatencyMs
                        ?: Long.MAX_VALUE
                }

        if (fastest == null) {
            toast(
                getString(
                    R.string
                        .no_ping_results
                )
            )
            return
        }

        repository.select(
            fastest.id
        )
        refresh()
        toast(
            getString(
                R.string
                    .fastest_selected,
                fastest.name,
                fastest.tcpLatencyMs
                    ?: 0
            )
        )
    }
'''
    new_fastest = '''    private fun selectFastestProfile() {
        val fastest =
            repository.all()
                .asSequence()
                .filter { it.enabled }
                .filter { dualLatencyTier(it) < 3 }
                .minWithOrNull(
                    compareBy<ConfigProfile> {
                        dualLatencyTier(it)
                    }.thenBy {
                        dualLatencyScore(it)
                    }.thenBy {
                        it.name.lowercase(Locale.ROOT)
                    }
                )

        if (fastest == null) {
            toast(
                getString(
                    R.string
                        .no_ping_results
                )
            )
            return
        }

        repository.select(
            fastest.id
        )
        refresh()
        toast(
            getString(
                R.string
                    .fastest_selected,
                fastest.name,
                dualLatencyScore(fastest)
            )
        )
    }
'''
    text = replace_once(text, old_fastest, new_fastest, "selectFastestProfile")

    helpers = '''    private fun dualLatencyTier(profile: ConfigProfile): Int {
        val tcpAlive =
            profile.tcpTestStatus == TestStatus.ALIVE &&
                profile.tcpLatencyMs != null
        val realAlive =
            profile.realTestStatus == TestStatus.ALIVE &&
                profile.realLatencyMs != null
        return when {
            tcpAlive && realAlive -> 0
            realAlive -> 1
            tcpAlive -> 2
            else -> 3
        }
    }

    private fun dualLatencyScore(profile: ConfigProfile): Long {
        val tcp = profile.tcpLatencyMs
            ?.takeIf { profile.tcpTestStatus == TestStatus.ALIVE }
        val real = profile.realLatencyMs
            ?.takeIf { profile.realTestStatus == TestStatus.ALIVE }
        return when {
            tcp != null && real != null ->
                ((tcp * 40L) + (real * 60L)) / 100L
            real != null -> real + SINGLE_METHOD_PENALTY_MS
            tcp != null -> tcp + SINGLE_METHOD_PENALTY_MS
            else -> Long.MAX_VALUE
        }
    }

'''
    comparator_anchor = '''    private fun profileComparator(
'''
    if helpers.strip() not in text:
        if comparator_anchor not in text:
            fail("Patch anchor not found: profileComparator")
        text = text.replace(comparator_anchor, helpers + comparator_anchor, 1)

    old_smart = '''                ProfileSortMode.SMART ->
                    compareByDescending<
                        ConfigProfile
                        > {
                        it.favorite
                    }
                        .thenBy {
                            if (
                                it.tcpTestStatus ==
                                TestStatus.ALIVE &&
                                it.tcpLatencyMs != null
                            ) 0 else 1
                        }
                        .thenBy {
                            it.tcpLatencyMs
                                ?: Long.MAX_VALUE
                        }
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }

                ProfileSortMode
                    .LOWEST_LATENCY ->
                    compareBy<
                        ConfigProfile
                        > {
                        if (
                            it.tcpTestStatus ==
                            TestStatus.ALIVE &&
                            it.tcpLatencyMs != null
                        ) 0 else 1
                    }
                        .thenBy {
                            it.tcpLatencyMs
                                ?: Long.MAX_VALUE
                        }
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }
'''
    new_smart = '''                ProfileSortMode.SMART ->
                    compareByDescending<
                        ConfigProfile
                        > {
                        it.favorite
                    }
                        .thenBy(::dualLatencyTier)
                        .thenBy(::dualLatencyScore)
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }

                ProfileSortMode
                    .LOWEST_LATENCY ->
                    compareBy<ConfigProfile>(
                        ::dualLatencyTier
                    )
                        .thenBy(::dualLatencyScore)
                        .thenBy {
                            it.name.lowercase(
                                Locale.ROOT
                            )
                        }
'''
    text = replace_once(text, old_smart, new_smart, "dual latency comparators")

    constant_anchor = '''        private const val PING_RESULT_FLUSH_MS = 220L
'''
    constant_new = '''        private const val PING_RESULT_FLUSH_MS = 220L
        private const val SINGLE_METHOD_PENALTY_MS = 5_000L
'''
    text = replace_once(text, constant_anchor, constant_new, "dual latency penalty constant")
    write(path, text, dry_run)


def replace_visible_words(value: str) -> str:
    replacements = [
        ("Configurations", "Configs"),
        ("configurations", "configs"),
        ("Configuration", "Config"),
        ("configuration", "config"),
        ("پیکربندی‌ها", "کانفیگ‌ها"),
        ("پیکربندی های", "کانفیگ‌های"),
        ("پیکربندی", "کانفیگ"),
    ]
    for old, replacement in replacements:
        value = value.replace(old, replacement)
    return value


def patch_xml_visible_text(text: str) -> str:
    import re

    # Text nodes. Tag names and resource identifiers are deliberately untouched.
    text = re.sub(
        r"(?<=>)([^<]+)(?=<)",
        lambda match: replace_visible_words(match.group(1)),
        text,
    )

    # Direct visible attributes in layouts/menus. Resource references stay intact.
    visible_attributes = (
        "text", "hint", "title", "summary", "contentDescription", "label"
    )
    attribute_pattern = re.compile(
        r'((?:android:)?(?:' + "|".join(visible_attributes) + r')\s*=\s*")([^"]*)(")'
    )

    def replace_attribute(match: "re.Match[str]") -> str:
        value = match.group(2)
        if value.startswith(("@", "?")):
            return match.group(0)
        return match.group(1) + replace_visible_words(value) + match.group(3)

    return attribute_pattern.sub(replace_attribute, text)


def patch_kotlin_visible_literals(text: str) -> str:
    import re

    string_pattern = re.compile(r'"(?:\\.|[^"\\])*"')

    def replace_literal(match: "re.Match[str]") -> str:
        literal = match.group(0)
        body = literal[1:-1]
        # Do not rename stable keys such as "configuration". Only human-facing
        # sentences/labels and capitalized words are shortened.
        if not (any(ch.isspace() for ch in body) or body.startswith("Configuration") or "پیکربندی" in body):
            return literal
        return '"' + replace_visible_words(body) + '"'

    return string_pattern.sub(replace_literal, text)


def add_string(path: Path, name: str, value: str, dry_run: bool) -> None:
    text = read(path)
    if f'name="{name}"' in text:
        return
    anchor = "</resources>"
    if anchor not in text:
        fail(f"Invalid Android string resource: {path}")
    escaped = value.replace("&", "&amp;").replace("<", "&lt;")
    text = text.replace(anchor, f'    <string name="{name}">{escaped}</string>\n{anchor}', 1)
    write(path, text, dry_run)


def patch_strings(root: Path, dry_run: bool) -> None:
    res = root / "app/src/main/res"
    source = root / "app/src/main/java"
    if not res.is_dir():
        fail(f"Missing Android resources: {res}")

    for path in sorted(res.rglob("*.xml")):
        text = patch_xml_visible_text(read(path))
        write(path, text, dry_run)

    if source.is_dir():
        for path in sorted(source.rglob("*.kt")):
            text = patch_kotlin_visible_literals(read(path))
            write(path, text, dry_run)

    english = res / "values/strings.xml"
    persian = res / "values-fa/strings.xml"
    if not english.is_file():
        candidates = sorted((res / "values").glob("strings*.xml"))
        english = candidates[0] if candidates else english
    if not persian.is_file():
        candidates = sorted((res / "values-fa").glob("strings*.xml"))
        persian = candidates[0] if candidates else persian

    add_string(english, "subscription_export_links", "Export config links", dry_run)
    add_string(english, "subscription_export_none", "No shareable config links exist in this subscription.", dry_run)
    add_string(english, "subscription_export_title", "%1$s config links", dry_run)
    add_string(persian, "subscription_export_links", "خروجی لینک‌های کانفیگ", dry_run)
    add_string(persian, "subscription_export_none", "در این ساب لینک کانفیگ قابل اشتراکی وجود ندارد.", dry_run)
    add_string(persian, "subscription_export_title", "لینک‌های کانفیگ %1$s", dry_run)

def copy_new_files(package_root: Path, repo_root: Path, dry_run: bool) -> None:
    included = [
        "app/src/main/java/com/trivox/client/network/BatchRealDelayRunner.kt",
        "app/src/main/java/com/trivox/client/ssh/OpenSshBinaryManager.kt",
        "app/src/main/java/com/trivox/client/ssh/OpenSshCommand.kt",
        "app/src/main/assets/openssh/manifest.json",
        "app/src/main/assets/openssh/README.txt",
        ".github/workflows/openssh-binaries.yml",
        "tools/openssh/build-openssh-android.sh",
        "tools/openssh/prepare-termux-properties.py",
        "tools/openssh/write-manifest.py",
    ]
    for relative in included:
        source = package_root / relative
        target = repo_root / relative
        if not source.is_file():
            fail(f"Packaged file is missing: {source}")
        if dry_run:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def main() -> int:
    parser = argparse.ArgumentParser(description=PATCH_NAME)
    parser.add_argument("repo", nargs="?", default=".", help="Trivox repository root")
    parser.add_argument("--check", action="store_true", help="Validate anchors without writing")
    args = parser.parse_args()

    package_root = Path(__file__).resolve().parents[1]
    repo_root = Path(args.repo).resolve()
    if not (repo_root / "settings.gradle.kts").is_file():
        fail(f"Not a Trivox repository root: {repo_root}")

    patch_ping_manager(repo_root, args.check)
    patch_main_activity(repo_root, args.check)
    patch_strings(repo_root, args.check)
    copy_new_files(package_root, repo_root, args.check)

    print("Validation passed." if args.check else "Patch applied successfully.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)

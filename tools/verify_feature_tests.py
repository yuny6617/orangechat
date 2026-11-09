#!/usr/bin/env python3
"""Guard that the targeted video-gift feature test set really ran.

The video-call/gift feature is covered by a fixed filter set (see
``docs/GITHUB_DUAL_BUILD.md``): 21 test classes, 224 test methods.  Gradle
silently "succeeds" when a ``--tests`` pattern matches nothing at all only in
some configurations, and a deleted or renamed class would quietly leave the
feature untested.  This script makes the CI build fail closed unless every
expected class produced results, the total test count did not drop below the
reviewed 224, and nothing failed or was skipped.

Usage:
    python tools/verify_feature_tests.py --results app/build/test-results/testDebugUnitTest
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

EXPECTED_TEST_CLASSES = (
    "me.rerere.rikkahub.data.ai.transformers.VideoCallDirectiveStageTest",
    "me.rerere.rikkahub.data.ai.transformers.VideoCallDirectivesTest",
    "me.rerere.rikkahub.data.video.FrameFileOwnershipTest",
    "me.rerere.rikkahub.data.video.JpegOrientationPolicyTest",
    "me.rerere.rikkahub.data.video.VideoCallCameraLifecycleTest",
    "me.rerere.rikkahub.data.video.VideoCallCapabilityStateTest",
    "me.rerere.rikkahub.data.video.VideoCallPolicyTest",
    "me.rerere.rikkahub.data.video.VideoCallPromptsTest",
    "me.rerere.rikkahub.data.video.VideoCallRuntimeTest",
    "me.rerere.rikkahub.data.video.VideoCallSerializationTest",
    "me.rerere.rikkahub.data.video.VideoCallTurnLedgerTest",
    "me.rerere.rikkahub.data.video.VideoDanmakuTest",
    "me.rerere.rikkahub.data.video.VideoFramePolicyTest",
    "me.rerere.rikkahub.data.video.VideoGiftAnnotationTest",
    "me.rerere.rikkahub.data.video.VideoGiftEffectQueueTest",
    "me.rerere.rikkahub.data.video.VideoGiftProtocolTest",
    "me.rerere.rikkahub.data.video.VideoPreviewGeometryTest",
    "me.rerere.rikkahub.service.MessageQueueTest",
    "me.rerere.rikkahub.service.VoiceCallMicrophoneTest",
    "me.rerere.rikkahub.service.VoiceCallServiceLifecycleTest",
    "me.rerere.rikkahub.service.VoiceCallTranscriptTest",
)

# Number of test methods the reviewed feature set contained (2026-... review).
MIN_TOTAL_TESTS = 224


@dataclass
class TestResults:
    total: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    per_class: dict[str, int] = field(default_factory=dict)


def parse_test_results(directory: str | Path) -> TestResults:
    """Aggregate JUnit XML results produced by Gradle's testDebugUnitTest."""

    path = Path(directory)
    if not path.is_dir():
        raise ValueError(f"test results directory not found: {path}")

    results = TestResults()
    files = sorted(path.rglob("TEST-*.xml"))
    for file in files:
        try:
            root = ElementTree.parse(file).getroot()
        except ElementTree.ParseError as error:
            raise ValueError(f"cannot parse {file}: {error}") from error

        test_cases = list(root.iter("testcase"))
        if test_cases:
            for case in test_cases:
                results.total += 1
                class_name = case.get("classname") or (root.get("name") or "")
                results.per_class[class_name] = results.per_class.get(class_name, 0) + 1
                if case.find("failure") is not None:
                    results.failures += 1
                if case.find("error") is not None:
                    results.errors += 1
                if case.find("skipped") is not None:
                    results.skipped += 1
        else:
            # Empty suites carry no testcase elements; still honour their counts.
            tests = int(root.get("tests") or 0)
            results.total += tests
            results.failures += int(root.get("failures") or 0)
            results.errors += int(root.get("errors") or 0)
            results.skipped += int(root.get("skipped") or 0)
            name = root.get("name") or ""
            if name:
                results.per_class[name] = results.per_class.get(name, 0) + tests
    return results


def check_results(
    results: TestResults,
    expected_classes: Sequence[str] = EXPECTED_TEST_CLASSES,
    min_total_tests: int = MIN_TOTAL_TESTS,
) -> list[str]:
    """Return every reason the feature test run must be considered failed."""

    errors: list[str] = []
    missing = [name for name in expected_classes if results.per_class.get(name, 0) == 0]
    for name in missing:
        errors.append(f"expected feature test class did not run: {name}")
    if results.total < min_total_tests:
        errors.append(
            f"only {results.total} feature tests ran; the reviewed set has {min_total_tests}"
        )
    if results.failures:
        errors.append(f"{results.failures} feature test(s) failed")
    if results.errors:
        errors.append(f"{results.errors} feature test(s) errored")
    if results.skipped:
        errors.append(f"{results.skipped} feature test(s) were skipped; skipping hides regressions")
    return errors


def summary_lines(results: TestResults) -> list[str]:
    return [
        f"featureTests={results.total}",
        f"featureTestClasses={len(results.per_class)}",
        f"failures={results.failures}",
        f"errors={results.errors}",
        f"skipped={results.skipped}",
    ]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", required=True, help="Gradle test-results directory")
    parser.add_argument("--min-total", type=int, default=MIN_TOTAL_TESTS)
    args = parser.parse_args(argv)

    try:
        results = parse_test_results(args.results)
    except ValueError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    for line in summary_lines(results):
        print(line)
    errors = check_results(results, min_total_tests=args.min_total)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

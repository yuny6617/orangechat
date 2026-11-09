"""Unit tests for tools/verify_feature_tests.py (CI guard for the 224 feature tests)."""

import tempfile
import unittest
from pathlib import Path

from verify_feature_tests import (
    EXPECTED_TEST_CLASSES,
    MIN_TOTAL_TESTS,
    TestResults,
    check_results,
    main,
    parse_test_results,
)


def suite_xml(class_name, tests, failures=0, errors=0, skipped=0, testcases=True):
    cases = ""
    if testcases:
        cases = "".join(
            f'<testcase classname="{class_name}" name="case{i}" time="0.001"/>'
            for i in range(tests - failures - errors - skipped)
        )
        cases += "".join(
            f'<testcase classname="{class_name}" name="failure{i}"><failure/></testcase>'
            for i in range(failures)
        )
        cases += "".join(
            f'<testcase classname="{class_name}" name="error{i}"><error/></testcase>'
            for i in range(errors)
        )
        cases += "".join(
            f'<testcase classname="{class_name}" name="skip{i}"><skipped/></testcase>'
            for i in range(skipped)
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<testsuite name="{class_name}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}">{cases}</testsuite>'
    )


def write_results(directory, counts):
    """counts: {class_name: test_count}; total matches MIN_TOTAL_TESTS by default."""

    for class_name, count in counts.items():
        filename = f"TEST-{class_name}.xml"
        (Path(directory) / filename).write_text(suite_xml(class_name, count), encoding="utf-8")


def default_counts():
    """Split the reviewed 224 tests across the expected classes."""

    counts = {}
    remaining = MIN_TOTAL_TESTS
    for index, class_name in enumerate(EXPECTED_TEST_CLASSES):
        count = remaining // (len(EXPECTED_TEST_CLASSES) - index)
        counts[class_name] = count
        remaining -= count
    return counts


class ParseResultsTest(unittest.TestCase):
    def test_aggregates_counts_and_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            write_results(directory, default_counts())
            results = parse_test_results(directory)
        self.assertEqual(results.total, MIN_TOTAL_TESTS)
        self.assertEqual(len(results.per_class), len(EXPECTED_TEST_CLASSES))
        self.assertEqual(results.failures, 0)
        self.assertEqual(results.errors, 0)
        self.assertEqual(results.skipped, 0)

    def test_missing_directory_is_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                parse_test_results(Path(directory) / "nope")

    def test_unparsable_xml_is_an_error(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "TEST-broken.xml").write_text("<testsuite", encoding="utf-8")
            with self.assertRaises(ValueError):
                parse_test_results(directory)

    def test_empty_suite_uses_testsuite_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "TEST-empty.xml").write_text(
                suite_xml("me.rerere.rikkahub.data.video.VideoGiftProtocolTest", 16, testcases=False),
                encoding="utf-8",
            )
            results = parse_test_results(directory)
        self.assertEqual(results.total, 16)
        self.assertEqual(results.per_class["me.rerere.rikkahub.data.video.VideoGiftProtocolTest"], 16)


class CheckResultsTest(unittest.TestCase):
    def valid_results(self):
        return TestResults(total=MIN_TOTAL_TESTS, per_class=default_counts())

    def test_complete_run_passes(self):
        self.assertEqual(check_results(self.valid_results()), [])

    def test_missing_class_is_rejected(self):
        results = self.valid_results()
        removed = EXPECTED_TEST_CLASSES[-1]
        results.total -= results.per_class.pop(removed)
        # Keep the total at 224 so only the missing-class check can fail.
        results.total = MIN_TOTAL_TESTS
        errors = check_results(results)
        self.assertTrue(any(removed in error for error in errors))

    def test_too_few_tests_is_rejected(self):
        results = TestResults(total=MIN_TOTAL_TESTS - 1, per_class=default_counts())
        errors = check_results(results)
        self.assertTrue(any("only 223 feature tests ran" in error for error in errors))

    def test_failures_are_rejected(self):
        results = self.valid_results()
        results.failures = 2
        errors = check_results(results)
        self.assertTrue(any("2 feature test(s) failed" in error for error in errors))

    def test_errors_are_rejected(self):
        results = self.valid_results()
        results.errors = 1
        errors = check_results(results)
        self.assertTrue(any("errored" in error for error in errors))

    def test_skips_are_rejected(self):
        results = self.valid_results()
        results.skipped = 3
        errors = check_results(results)
        self.assertTrue(any("skipped" in error for error in errors))


class CliTest(unittest.TestCase):
    def test_cli_returns_zero_for_complete_run(self):
        with tempfile.TemporaryDirectory() as directory:
            write_results(directory, default_counts())
            exit_code = main(["--results", directory])
        self.assertEqual(exit_code, 0)

    def test_cli_returns_non_zero_when_a_class_is_missing(self):
        counts = default_counts()
        counts.pop(EXPECTED_TEST_CLASSES[0])
        with tempfile.TemporaryDirectory() as directory:
            write_results(directory, counts)
            exit_code = main(["--results", directory])
        self.assertEqual(exit_code, 1)


if __name__ == "__main__":
    unittest.main()

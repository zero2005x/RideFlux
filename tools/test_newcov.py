"""Synthetic regression tests for the local coverage gate (standard library only)."""
import re
import contextlib
import io
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from newcov import added_lines, coverage, exclusions, glob_regex, main, measure


class NewCoverageTest(unittest.TestCase):
    def test_added_lines_excludes_context_deletions_and_no_newline_marker(self):
        diff = """diff --git a/a.kt b/a.kt
--- a/a.kt
+++ b/a.kt
@@ -7,4 +7,5 @@
 context
-removed
+added
+another
 unchanged
\\ No newline at end of file
@@ -30,0 +32 @@
+last
"""
        self.assertEqual({"a.kt": {8, 9, 32}}, added_lines(diff))

    def test_deleted_file_is_not_counted(self):
        self.assertEqual({}, added_lines("--- a/a.kt\n+++ /dev/null\n@@ -1 +0,0 @@\n-deleted"))

    def test_sonar_globs_keep_single_star_inside_directory(self):
        pattern = glob_regex("**/pages/*Page.kt")
        self.assertTrue(pattern.fullmatch("pages/OnePage.kt"))
        self.assertTrue(pattern.fullmatch("app/pages/OnePage.kt"))
        self.assertFalse(pattern.fullmatch("app/pages/nested/OnePage.kt"))
        self.assertTrue(glob_regex("**/src/debug/**").fullmatch("app/src/debug/a/b.kt"))
        self.assertTrue(glob_regex("a?.kt").fullmatch("ab.kt"))

    def test_measure_counts_line_and_branch_hits_and_reports_missing_source(self):
        root = ET.fromstring('''<report><package name="com/example"><sourcefile name="A.kt">
          <line nr="10" mi="0" ci="1" mb="1" cb="1"/>
          <line nr="11" mi="1" ci="0" mb="2" cb="0"/>
          <line nr="12" mi="1" ci="0" mb="0" cb="1"/>
        </sourcefile></package></report>''')
        path = "module/src/main/kotlin/com/example/A.kt"
        missing_path = "module/src/main/java/com/example/Missing.kt"
        rows, missing = measure({path: {10, 11, 12, 13}, missing_path: {1},
                                 "module/src/test/kotlin/Test.kt": {1}}, coverage(root), [])
        self.assertEqual((2, 3, 2, 5), rows[0][1:5])
        self.assertEqual([missing_path], missing)

    def test_excluded_source_does_not_require_report_entry(self):
        rows, missing = measure({"app/src/main/kotlin/Screen.kt": {1}}, {}, [re.compile(".*Screen.kt")])
        self.assertEqual(([], []), (rows, missing))

    def test_duplicate_source_lines_fail_instead_of_double_counting(self):
        root = ET.fromstring('<report><package name="p"><sourcefile name="A.kt"><line nr="1"/><line nr="1"/></sourcefile></package></report>')
        with self.assertRaises(ValueError):
            coverage(root)

    def test_reads_actual_repository_exclusions(self):
        patterns = exclusions(Path(__file__).resolve().parents[1] / "build.gradle.kts")
        self.assertTrue(any(p.fullmatch("app/src/main/kotlin/com/rideflux/app/ui/bond/BondBackupScreen.kt") for p in patterns))
        self.assertFalse(any(p.fullmatch("domain/src/main/kotlin/com/rideflux/domain/bond/BondBackup.kt") for p in patterns))

    def test_cli_returns_failure_for_missing_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            diff = root / "diff.txt"
            xml = root / "jacoco.xml"
            diff.write_text("+++ b/module/src/main/kotlin/p/A.kt\n@@ -0,0 +1 @@\n+code", encoding="utf-8")
            xml.write_text("<report/>", encoding="utf-8")
            output, errors = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                result = main([str(diff), str(xml)])
            self.assertEqual(2, result)
            self.assertIn("changed source absent", errors.getvalue())

    def test_cli_reports_combined_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            diff = root / "diff.txt"
            xml = root / "jacoco.xml"
            diff.write_text("+++ b/module/src/main/kotlin/p/A.kt\n@@ -0,0 +1 @@\n+code", encoding="utf-8")
            xml.write_text('<report><package name="p"><sourcefile name="A.kt"><line nr="1" ci="1" mb="1" cb="1"/></sourcefile></package></report>', encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = main([str(diff), str(xml), "A.kt"])
            self.assertEqual(0, result)
            self.assertIn("Sonar-style (lines+conditions): 66.7%", output.getvalue())
            self.assertIn("1(1/2)", output.getvalue())


if __name__ == "__main__":
    unittest.main()

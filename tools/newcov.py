#!/usr/bin/env python3
"""Measure changed Kotlin executable lines and conditions against JaCoCo XML.

Usage: python3 tools/newcov.py DIFF XML [FileName.kt ...]
Create DIFF with git diff -U0 BASE. Missing sources fail closed: this tool
cannot infer executable lines from Kotlin text without the compiled report.
Exclusions are read from the repository's sonar.coverage.exclusions setting.
"""
import argparse
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


def added_lines(diff):
    added = {}
    current = None
    cursor = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            current = line[6:] if line.startswith("+++ b/") else None
            cursor = None
        elif line.startswith("@@ "):
            match = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@", line)
            cursor = int(match.group(1)) if match else None
        elif current is not None and cursor is not None:
            if line.startswith("+"):
                added.setdefault(current, set()).add(cursor)
                cursor += 1
            elif line.startswith(" "):
                cursor += 1
            elif line.startswith("diff --git "):
                current = None
                cursor = None
    return added


def glob_regex(pattern):
    """Sonar path globs: a single star never crosses a slash."""
    result = []
    index = 0
    while index < len(pattern):
        if pattern[index:index + 3] == "**/":
            result.append("(?:.*/)?")
            index += 3
        elif pattern[index:index + 2] == "**":
            result.append(".*")
            index += 2
        elif pattern[index] == "*":
            result.append("[^/]*")
            index += 1
        elif pattern[index] == "?":
            result.append("[^/]")
            index += 1
        else:
            result.append(re.escape(pattern[index]))
            index += 1
    return re.compile("^" + "".join(result) + "$")


def exclusions(build_file):
    text = build_file.read_text(encoding="utf-8")
    match = re.search(r'"sonar\.coverage\.exclusions"\s*,\s*listOf\((.*?)\)\.joinToString', text, re.S)
    if not match:
        raise ValueError("Cannot find sonar.coverage.exclusions in build.gradle.kts")
    # Strip comments so quoted explanatory text cannot become a pattern.
    block = re.sub(r"//[^\n]*", "", match.group(1))
    return [glob_regex(value) for value in re.findall(r'"([^"\n]+)"', block)]


def coverage(root):
    sources = {}
    for package in root.iter("package"):
        for source in package.findall("sourcefile"):
            key = package.get("name", "") + "/" + source.get("name", "")
            lines = sources.setdefault(key, {})
            for line in source.findall("line"):
                covered = int(line.get("ci", "0")) > 0 or int(line.get("cb", "0")) > 0
                branches = int(line.get("mb", "0")) + int(line.get("cb", "0"))
                hit_branches = int(line.get("cb", "0"))
                number = int(line.get("nr"))
                if number in lines:
                    raise ValueError("Duplicate JaCoCo source line: " + key)
                lines[number] = (covered, branches, hit_branches)
    return sources


def measure(added, sources, patterns):
    rows = []
    missing = []
    for path, changes in sorted(added.items()):
        if not path.endswith(".kt") or any(pattern.fullmatch(path) for pattern in patterns):
            continue
        match = re.search(r"/src/main/(?:kotlin|java)/(.*)$", path)
        if not match:
            continue
        source = sources.get(match.group(1))
        if source is None:
            missing.append(path)
            continue
        executable = [(number, source[number]) for number in sorted(changes) if number in source]
        total = len(executable)
        hit = sum(int(value[0]) for _, value in executable)
        branch_total = sum(value[1] for _, value in executable)
        branch_hit = sum(value[2] for _, value in executable)
        rows.append((path, hit, total, branch_hit, branch_total, executable))
    return rows, missing


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("diff", type=Path)
    parser.add_argument("xml", type=Path)
    parser.add_argument("files", nargs="*")
    parser.add_argument("--build-file", type=Path, default=Path(__file__).resolve().parents[1] / "build.gradle.kts")
    args = parser.parse_args(argv)
    rows, missing = measure(
        added_lines(args.diff.read_text(encoding="utf-8", errors="replace")),
        coverage(ET.parse(args.xml).getroot()), exclusions(args.build_file),
    )
    hit, total, branch_hit, branch_total = [sum(row[index] for row in rows) for index in range(1, 5)]
    print("new executable lines: %d covered: %d => %.1f%%" % (total, hit, 100 * hit / max(total, 1)))
    print("new conditions: %d covered: %d" % (branch_total, branch_hit))
    print("Sonar-style (lines+conditions): %.1f%%" % (100 * (hit + branch_hit) / max(total + branch_total, 1)))
    for path, line_hit, line_total, cond_hit, cond_total, executable in sorted(
        rows, key=lambda row: row[2] - row[1] + row[4] - row[3], reverse=True,
    )[:25]:
        print("%4d missing (lines %d/%d, cond missed %d) %s" % (
            line_total - line_hit + cond_total - cond_hit, line_hit, line_total, cond_total - cond_hit, path,
        ))
        if any(path.endswith(name) for name in args.files):
            uncovered = ["%d%s(%d/%d)" % (number, "" if value[0] else "!", value[2], value[1])
                         for number, value in executable if not value[0] or value[2] < value[1]]
            print(path, " ".join(uncovered))
    for path in missing:
        print("ERROR: changed source absent from JaCoCo report: " + path, file=sys.stderr)
    return 2 if missing else 0


if __name__ == "__main__":
    sys.exit(main())

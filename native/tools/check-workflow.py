"""Validate .github/workflows/native.yml without a runner.

This workflow has never executed successfully - its jobs have been queued in GitHub's
runner backlog since 17f5833c - and its library names were just edited to match what cmake
actually produces. So the structure is checked here rather than trusted.
"""

import sys

try:
    import yaml
except ImportError:
    print("pyyaml is not available")
    sys.exit(2)

with open(".github/workflows/native.yml", encoding="utf-8") as handle:
    doc = yaml.safe_load(handle)

print("YAML parses OK")
jobs = doc.get("jobs", {})
print("jobs: " + ", ".join(jobs))

problems = []

for name, job in jobs.items():
    matrix = (job.get("strategy") or {}).get("matrix") or {}
    entries = matrix.get("include", [])
    print("  " + name + ": runs-on=" + str(job.get("runs-on"))
          + ", needs=" + str(job.get("needs")) + ", matrix entries=" + str(len(entries)))
    for entry in entries:
        print("      " + str(entry.get("platform")) + " -> " + str(entry.get("library")))

# Every needs target has to exist or no job starts.
for name, job in jobs.items():
    needs = job.get("needs") or []
    if isinstance(needs, str):
        needs = [needs]
    for target in needs:
        if target not in jobs:
            problems.append("needs unknown job: " + str(target))

# The library names must match PREFIX "" in CMakeLists, or the verification step reports a
# missing artefact on a build that succeeded.
for job_name in ("build-and-test",):
    job = jobs.get(job_name) or {}
    matrix = (job.get("strategy") or {}).get("matrix") or {}
    for entry in matrix.get("include", []):
        library = entry.get("library", "")
        if library.startswith("lib"):
            problems.append("library name still carries a lib prefix: " + library)

# A step that greps for a name is the part that actually failed before, so check the raw text.
raw = open(".github/workflows/native.yml", encoding="utf-8").read()
if "libwrobj" in raw:
    problems.append("the workflow still mentions libwrobj somewhere")

if problems:
    for problem in problems:
        print("PROBLEM: " + problem)
    sys.exit(1)
print("no structural problems found")

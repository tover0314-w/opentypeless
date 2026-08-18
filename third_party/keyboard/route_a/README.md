# Route A upstream replay inputs

This directory contains the KSP-011 maintained, source-only replay contract for the
restricted Route A evaluation boundary accepted by ADR-0011.

It does **not** import the whole FlorisBoard application, authorize product integration,
bundle native libraries, or distribute real Xiaohè data. The historical KSP-009 `final3`
binary patch remains external evidence only because committing it would retain generated
`.so` files and the preimages of deleted, source-unverified resources.

The queue is deliberately finite:

1. build wiring and pinned Gradle verification metadata;
2. the capability-confined OpenTypeless editor-host module;
3. the isolated Route A safety evaluation module and trusted gates.

Run the offline checks with:

```bash
python3 scripts/route_a_upstream.py verify --repo-root .
python3 scripts/route_a_upstream.py verify-source \
  --repo-root . \
  --upstream-repo /path/to/clean/florisboard
python3 scripts/route_a_upstream.py replay \
  --repo-root . \
  --archive /path/to/florisboard-fixed.tar.gz \
  --output-dir /new/empty/path \
  --report /path/to/report.json
```

`verify` and `replay` never fetch. The archive URL in the lock is an identity record, not
an implicit network permission. REL-009 owns a future upstream-version update and conflict
resolution; KSP-011 only proves deterministic replay of the fixed input.

The lock deliberately records two trees: the official Git commit tree proves repository
ancestry, while the materialized archive tree proves the normalized index used for offline
patch application. The archive contains three upstream-tracked `.idea` files which also match
upstream ignore rules, so replay force-adds every preflighted regular member before applying the
queue; omitting those tracked files is a hard failure.

# Debug APK distribution kit

These files are the source of truth for publishing throwaway-signed debug APKs
to the private input repository `bharti1233/hmx-build-inputs`.

They are **not** active in this public repository. Copy them into the private
repository:

| Source here | Destination in `hmx-build-inputs` |
| --- | --- |
| `distribution/cleanup-debug-releases.yml` | `.github/workflows/cleanup-debug-releases.yml` |
| `distribution/cleanup_debug_releases.sh` | `scripts/cleanup_debug_releases.sh` |

## Layout in the private repository

```text
releases/tag/Apk-input-original        protected input, never touched
releases/tag/debug-test-<run>-<attempt> throwaway test build, deleted after 72h
```

## Tokens

| Secret | Scope | Used by |
| --- | --- | --- |
| `BASE_APK_TOKEN` | `hmx-build-inputs`, Contents: **Read** | input download |
| `DIST_TOKEN` | `hmx-build-inputs`, Contents: **Read and write** | debug publishing |

Both live in the `apk-build` environment of the public repository. They must be
different tokens; the workflow fails if they are equal.

## Deletion gate

`cleanup-debug-releases.yml` is dry-run only until the repository variable
`CLEANUP_DELETE_ENABLED` is set to `true` in `hmx-build-inputs`:

- schedule: every 6 hours, dry-run while the gate is closed;
- manual `workflow_dispatch`: defaults to `dry_run=true` regardless.

Deletion only happens for releases that are still open when:

1. the tag matches `^debug-test-[0-9]+-[0-9]+$` exactly;
2. the release is a prerelease;
3. the tag is not `Apk-input-original` and the numeric release id is not the
   protected release id;
4. `created_at` is older than 72 hours.

If the protected release cannot be resolved, the cleanup aborts without
deleting anything.

## Downloading on Android

1. Sign in to GitHub in Chrome as the repository owner.
2. Open `https://github.com/bharti1233/hmx-build-inputs/releases`.
3. Open the `debug-test-<run>-<attempt>` prerelease.
4. Download `hmx-insta-<version>-test.apk` and compare its SHA-256 with the
   value in the release body.
5. Install it. The throwaway signature cannot update over the stock Instagram
   app; uninstall it first or build with `clone=true`.

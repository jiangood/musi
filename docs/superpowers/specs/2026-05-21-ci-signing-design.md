# CI Signing for Literalmusi

## Problem

The CI workflow builds an unsigned debug APK and attaches it to GitHub releases. A
signed APK is needed for side-loading without requiring users to disable play
protect or accept unknown developer warnings.

## Design

### One-time user setup

Generate a keystore and configure GitHub secrets:

```bash
keytool -genkey -v -keystore literal-musi.jks -alias literal-musi \
  -keyalg RSA -keysize 2048 -validity 10000
base64 literal-musi.jks  # copy output
```

Add these secrets to the GitHub repository:

| Secret                    | Value                                 |
| ------------------------- | ------------------------------------- |
| `SIGNING_KEYSTORE`        | base64-encoded content of the keystore |
| `SIGNING_KEYSTORE_PASSWORD` | Keystore/key password (shared)        |
| `SIGNING_KEY_ALIAS`       | `literal-musi`                        |

`CI_KEYSTORE_PATH` is removed — the temp path is hardcoded in CI.
`STORE_PASSWORD` and `KEY_PASSWORD` are consolidated into a single
`SIGNING_KEYSTORE_PASSWORD`.

### CI workflow changes (`.github/workflows/build.yml`)

1. **New step** — Decode the base64 keystore into a file before the build step:
   ```yaml
   - name: Decode keystore
     run: echo "${{ secrets.SIGNING_KEYSTORE }}" | base64 -d > /tmp/literal-musi.jks
   ```

2. **Build step** — Switch from `assembleDebug` to `assembleRelease`.

3. **Upload artifact path** — Change from `apk/debug/` to `apk/release/`.

4. **Release files path** — Same path change for the release step.

### build.gradle.kts changes

Rename env vars in `app/build.gradle.kts:29-44` to match the new naming:

| Old                  | New                       |
| -------------------- | ------------------------- |
| `CI_KEYSTORE_PATH`   | (replaced by hardcoded path in CI, but keep reading it as fallback) |
| `STORE_PASSWORD`     | `SIGNING_KEYSTORE_PASSWORD` |
| `KEY_ALIAS`          | `SIGNING_KEY_ALIAS`       |
| `KEY_PASSWORD`       | (removed, use `SIGNING_KEYSTORE_PASSWORD` for both) |

### Verification

- **PR push:** `assembleRelease` runs with signing; signed APK is uploaded as
  artifact.
- **Tag push (`v*`):** A signed APK is attached to the GitHub release.

## Flows

### PR / branch push flow
```
checkout → JDK 17 → Android SDK → decode keystore → assembleRelease → upload artifact
```

### Tag push flow (extends the above)
```
... → assembleRelease → upload artifact → create GitHub release with signed APK
```

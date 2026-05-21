# CI Signing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sign release APKs in CI using a keystore stored as a GitHub secret.

**Architecture:** Leverage existing Gradle signing config that reads env vars. Add a CI step to decode a base64-encoded keystore, rename env vars for consistency (`SIGNING_*` prefix), and switch from `assembleDebug` to `assembleRelease`.

**Tech Stack:** Gradle (Kotlin DSL), GitHub Actions, Android SDK

---

### Task 1: Update env var names in build.gradle.kts

**Files:**
- Modify: `app/build.gradle.kts:29-44`

- [ ] **Step 1: Rename env vars in the CI signing config branch**

  Current code (lines 29-44):
  ```kotlin
      signingConfigs {
          create("release") {
              val ciKeystorePath = System.getenv("CI_KEYSTORE_PATH")
              val ciKeystoreFile = ciKeystorePath?.let { file(it) }
              if (ciKeystoreFile?.exists() == true) {
                  storeFile = ciKeystoreFile
                  storePassword = System.getenv("STORE_PASSWORD") ?: ""
                  keyAlias = System.getenv("KEY_ALIAS") ?: ""
                  keyPassword = System.getenv("KEY_PASSWORD") ?: ""
              } else if (localPropertiesFile.exists()) {
  ```

  Replace with:
  ```kotlin
      signingConfigs {
          create("release") {
              val ciKeystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
              val ciKeystoreFile = ciKeystorePath?.let { file(it) }
              if (ciKeystoreFile?.exists() == true) {
                  storeFile = ciKeystoreFile
                  storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD") ?: ""
                  keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: ""
                  keyPassword = System.getenv("SIGNING_KEYSTORE_PASSWORD") ?: ""
              } else if (localPropertiesFile.exists()) {
  ```

  Changes:
  - `CI_KEYSTORE_PATH` → `SIGNING_KEYSTORE_PATH`
  - `STORE_PASSWORD` → `SIGNING_KEYSTORE_PASSWORD`
  - `KEY_ALIAS` → `SIGNING_KEY_ALIAS`
  - `KEY_PASSWORD` → `SIGNING_KEYSTORE_PASSWORD` (same as store password)

- [ ] **Step 2: Commit**

  ```bash
  git add app/build.gradle.kts
  git commit -m "refactor: rename signing env vars to SIGNING_* prefix"
  ```

---

### Task 2: Update CI workflow for release signing

**Files:**
- Modify: `.github/workflows/build.yml`

- [ ] **Step 1: Add keystore decode step and switch to release build**

  Current workflow (lines 28-46):
  ```yaml
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: musis-debug
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error

      - name: Create Release
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/debug/*.apk
          generate_release_notes: true
  ```

  Replace with:
  ```yaml
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Decode keystore
        run: echo "${{ secrets.SIGNING_KEYSTORE }}" | base64 -d > /tmp/literal-musi.jks
        env:
          SIGNING_KEYSTORE_PATH: /tmp/literal-musi.jks

      - name: Build Release APK
        run: ./gradlew assembleRelease --no-daemon
        env:
          SIGNING_KEYSTORE_PATH: /tmp/literal-musi.jks
          SIGNING_KEYSTORE_PASSWORD: ${{ secrets.SIGNING_KEYSTORE_PASSWORD }}
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: musis-release
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error

      - name: Create Release
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/release/*.apk
          generate_release_notes: true
  ```

  Key changes:
  - Added "Decode keystore" step that writes the base64 secret to `/tmp/literal-musi.jks`
  - Changed build from `assembleDebug` to `assembleRelease` with signing env vars
  - Changed upload path from `apk/debug/` to `apk/release/`
  - Changed artifact name from `musis-debug` to `musis-release`

- [ ] **Step 2: Commit**

  ```bash
  git add .github/workflows/build.yml
  git commit -m "ci: sign release APK with keystore from secrets"
  ```

---

### Task 3: Update design doc to reflect final state

**Files:**
- Modify: `docs/superpowers/specs/2026-05-21-ci-signing-design.md`

- [ ] **Step 1: Verify the design doc already matches the implementation**

  The design doc was already updated in the brainstorming phase. Read it to confirm:
  ```bash
  cat docs/superpowers/specs/2026-05-21-ci-signing-design.md
  ```

  If any details differ from the actual code changes, update them to match.

- [ ] **Step 2: Commit any doc changes**

  ```bash
  git add docs/superpowers/specs/2026-05-21-ci-signing-design.md
  git commit -m "docs: align signing design with final implementation"
  ```

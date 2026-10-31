# Build and continuous integration

## Conventions

- Kotlin 2.1.0, Compose BOM 2025.01.01, AGP 8.13.2, Gradle 9.0.0, JDK 17.
- Android compile/target SDK 35, build-tools 35.0.0; min SDK 29.
- Gradle wrapper with a distribution SHA-256; the wrapper JAR was compared with the digest published by Gradle: `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3`.
- GitHub Actions pinned to the commit of their official tags. Pull requests and the build workflow only get `contents: read` and never receive signing secrets. The separate `Android Release` workflow gets the secrets only for a trusted release tag.

## Local build

```sh
cd android
./gradlew --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
python3 -m unittest discover -s ../scripts -p 'test_*.py'
```

Point `ANDROID_HOME` to an existing SDK or create your own `local.properties` (ignored by Git). The asdf Java pin is in `android/.tool-versions`. On a machine with little RAM, set `TMPDIR` (and `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$TMPDIR`) to a disk with space and avoid parallel builds. Neither Android Studio nor an emulator is needed for these checks. A full build takes about 5 minutes on a small VPS.

`FloatingControlTest` renders the bubble in every state under Robolectric; set `BUBBLE_SNAPSHOTS=/some/dir` to write PNG snapshots.

## GitHub Actions

`.github/workflows/android-build.yml` runs on **every push to main**, on pull requests and manually. It validates the wrapper, installs the SDK explicitly, runs tests/lint/builds and uploads:

- `localdictate-debug-<SHA>`: debug APK and SHA256SUMS, for development only.
- `localdictate-reports-<SHA>`: JUnit/lint results, available even if the build fails.

Debug APKs from CI runs may have different certificates: they are not a stable update channel.

## Releases

`.github/workflows/android-release.yml` publishes signed APKs. Procedure:

1. Write the release notes in `docs/releases/vX.Y.Z.md` (required by the workflow).
2. Commit to `main`, push, and wait for **Android Build** to be green on that exact SHA.
3. Create an annotated tag `android-vX.Y.Z` on that SHA and push it.
4. **Android Release** checks that the commit belongs to `main` with a green build, computes the version from the tag, signs with the project's dedicated key (repository secrets `SIGNING_*`), verifies the APK (`apksigner`, package, version, ABI, 16 KiB alignment, no model weights) and publishes `LocalDictate-vX.Y.Z.apk` plus `SHA256SUMS`. It refuses to replace an existing release.
5. Download the published APK and check its checksum and certificate before announcing it.

The signing key is dedicated to this app and kept outside the repository; it must never be reused for another app. Never move a tag whose release exists.

## Milestone checks

Check CI for the exact SHA and the remote `main` branch. Download the real artifact and verify its checksum/manifest. Count tests from the JUnit XML files, not from terminal estimates. JVM/Compose tests do not validate the microphone, JNI, insertion in other apps, airplane mode or real durations on a phone.

## Read-only HOME

Robolectric's Maven resolver tries to create `~/.robolectric-download-lock`. A restricted environment can use an already cached Robolectric SDK without writing to HOME by passing `-ProbolectricDependencyDir=/path/to/folder/containing/android-all-instrumented-15-robolectric-12650502-i7.jar`. This only configures the test worker in offline mode; it disables no test and changes no global configuration.

The build resolves the sherpa-onnx 2.0.1 AAR through `fetchSherpa`/`builtBy` (pinned SHA and size). Gradle never downloads ASR/VAD weights. APKs contain only the arm64-v8a ABI, and JNI classes are kept in release builds.

## Insertion API isolation

Min SDK stays 29. The enabled accessibility components are selected by resources (base component on 29–32, direct editor from 33). The common class has no API 33 InputMethod types in its signatures. R8 rules keep the API 33 helper, component and callbacks separate. Loading on a real Android 29 device is a separate check that Robolectric SDK 35 does not prove.

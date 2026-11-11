package com.aiapp.reverse

/**
 * GitHub Actions workflow used by [ReverseReconstructRunner] (JADX Reconstruct).
 *
 * Compared with the first version it:
 * - downloads the newest jadx release (falls back to 1.5.3),
 * - turns on jadx's renaming of short/unreadable names so more of the code compiles,
 * - adds what Android Studio needs to open the result: the Gradle wrapper that matches the
 *   project's Android Gradle Plugin, gradle.properties (AndroidX on) and proguard rules,
 * - writes DECOMPILE_REPORT.md with the versions used and how many methods jadx could not recover.
 */
object ReconstructWorkflow {
    val YML: String = """
            name: Decompile for reconstruction
            on: push
            permissions:
              contents: write
            jobs:
              decompile:
                runs-on: ubuntu-latest
                timeout-minutes: 40
                steps:
                  - uses: actions/checkout@v4
                  - name: Get the newest jadx
                    env:
                      GH_TOKEN: ${'$'}{{ github.token }}
                    run: |
                      FALLBACK=https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip
                      URL=${'$'}(curl -sSL -H "Authorization: Bearer ${'$'}GH_TOKEN" https://api.github.com/repos/skylot/jadx/releases/latest | grep -oE 'https://[^"]*/jadx-[0-9][0-9.]*\.zip' | head -n 1 || true)
                      if [ -z "${'$'}URL" ]; then URL=${'$'}FALLBACK; fi
                      echo "Using ${'$'}URL"
                      curl -fsSL -o jadx.zip "${'$'}URL" || curl -fsSL -o jadx.zip "${'$'}FALLBACK"
                      unzip -q jadx.zip -d jadx || { rm -rf jadx jadx.zip; curl -fsSL -o jadx.zip "${'$'}FALLBACK"; unzip -q jadx.zip -d jadx; }
                      chmod +x jadx/bin/jadx
                  - name: Decompile with jadx (readable names, exported as an Android Studio project)
                    run: |
                      export JAVA_OPTS="-Xmx6g"
                      ./jadx/bin/jadx -e --export-gradle-type android-app --deobf --deobf-min 2 -j 4 -d decompiled app.apk 2>&1 | tee jadx.log || true
                      if [ ! -f decompiled/app/build.gradle ] && [ ! -f decompiled/build.gradle ]; then
                        echo "Retrying with basic options"
                        rm -rf decompiled
                        ./jadx/bin/jadx -e --export-gradle-type android-app -d decompiled app.apk 2>&1 | tee jadx.log || true
                      fi
                      if [ ! -d decompiled ]; then
                        echo "::error::jadx produced no output"
                        tail -60 jadx.log | while IFS= read -r l; do echo "::error::${'$'}{l//%/%25}"; done
                        exit 1
                      fi
                  - name: Add the files Android Studio needs (Gradle wrapper, gradle.properties, proguard rules)
                    run: |
                      cd decompiled
                      AGP=${'$'}(grep -rhoE "com\.android\.tools\.build:gradle:[0-9]+\.[0-9]+(\.[0-9]+)?" --include='*.gradle' --include='*.gradle.kts' . | head -n 1 | grep -oE "[0-9]+\.[0-9]+(\.[0-9]+)?${'$'}" || true)
                      MM=${'$'}(echo "${'$'}{AGP:-8.7}" | cut -d. -f1,2)
                      case "${'$'}MM" in
                        9.5|9.4) G=9.6.0 ;;
                        9.3) G=9.5.0 ;;
                        9.2) G=9.4.1 ;;
                        9.1) G=9.3.1 ;;
                        9.0) G=9.1.0 ;;
                        8.13|8.12|8.11) G=8.13 ;;
                        8.10|8.9) G=8.11.1 ;;
                        8.8) G=8.10.2 ;;
                        8.7) G=8.9 ;;
                        8.6|8.5) G=8.7 ;;
                        8.4) G=8.6 ;;
                        8.3) G=8.4 ;;
                        8.2) G=8.2 ;;
                        8.*) G=8.0 ;;
                        7.*) G=7.5 ;;
                        *) G=${'$'}(curl -fsSL https://services.gradle.org/versions/current | grep -oE '"version" *: *"[0-9.]+"' | grep -oE '[0-9][0-9.]*' | head -n 1 || true) ;;
                      esac
                      if [ -z "${'$'}G" ]; then G=8.14.3; fi
                      TAG="v${'$'}G"
                      case "${'$'}G" in *.*.*) ;; *) TAG="v${'$'}G.0" ;; esac
                      echo "Android Gradle Plugin ${'$'}{AGP:-unknown} -> Gradle ${'$'}G"
                      RAW=https://raw.githubusercontent.com/gradle/gradle/${'$'}TAG
                      if [ ! -f gradlew ]; then
                        mkdir -p gradle/wrapper
                        curl -fsSL -o gradlew "${'$'}RAW/gradlew" && chmod +x gradlew || echo "::warning::Couldn't download gradlew"
                        curl -fsSL -o gradlew.bat "${'$'}RAW/gradlew.bat" || echo "::warning::Couldn't download gradlew.bat"
                        curl -fsSL -o gradle/wrapper/gradle-wrapper.jar "${'$'}RAW/gradle/wrapper/gradle-wrapper.jar" || echo "::warning::Couldn't download gradle-wrapper.jar"
                        EXPECTED=${'$'}(curl -fsSL "https://services.gradle.org/distributions/gradle-${'$'}G-wrapper.jar.sha256" || true)
                        ACTUAL=${'$'}(sha256sum gradle/wrapper/gradle-wrapper.jar 2>/dev/null | cut -d' ' -f1 || true)
                        if [ -n "${'$'}EXPECTED" ] && [ "${'$'}EXPECTED" != "${'$'}ACTUAL" ]; then
                          echo "::warning::gradle-wrapper.jar checksum mismatch; leaving it out"
                          rm -f gradle/wrapper/gradle-wrapper.jar
                        fi
                        {
                          echo "distributionBase=GRADLE_USER_HOME"
                          echo "distributionPath=wrapper/dists"
                          echo "distributionUrl=https\\://services.gradle.org/distributions/gradle-${'$'}G-bin.zip"
                          echo "networkTimeout=10000"
                          echo "validateDistributionUrl=true"
                          echo "zipStoreBase=GRADLE_USER_HOME"
                          echo "zipStorePath=wrapper/dists"
                        } > gradle/wrapper/gradle-wrapper.properties
                      fi
                      if [ ! -f gradle.properties ]; then
                        printf 'org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.enableJetifier=true\n' > gradle.properties
                      fi
                      if [ -d app ] && [ ! -f app/proguard-rules.pro ]; then
                        echo "# Add project specific ProGuard rules here." > app/proguard-rules.pro
                      fi
                      ERRORS=${'$'}(grep -oE "finished with errors, count: [0-9]+" ../jadx.log | grep -oE "[0-9]+${'$'}" | tail -n 1 || true)
                      {
                        echo "# Decompiled project"
                        echo
                        echo "- Decompiled with: ${'$'}(../jadx/bin/jadx --version 2>/dev/null | tail -n 1)"
                        echo "- Android Gradle Plugin: ${'$'}{AGP:-unknown}, Gradle wrapper: ${'$'}G"
                        echo "- Methods jadx reported problems with: ${'$'}{ERRORS:-0} (methods jadx could not turn back into Java are left as stubs)"
                        echo "- Short or unreadable names were renamed by jadx so that the code can compile."
                        echo
                        echo "Open this folder in Android Studio (File > Open). local.properties is created by Android Studio."
                      } > DECOMPILE_REPORT.md
                  - name: Zip the project
                    run: cd decompiled && zip -qr ../project.zip . && cd ..
                  - uses: softprops/action-gh-release@v2
                    with:
                      tag_name: reconstruct-${'$'}{{ github.run_number }}
                      name: Reconstructed project ${'$'}{{ github.run_number }}
                      files: project.zip
                      make_latest: true
    """.trimIndent()
}

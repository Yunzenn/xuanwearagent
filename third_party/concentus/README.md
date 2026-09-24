# Frozen Concentus Java dependency

- Repository: https://github.com/lostromb/concentus
- Commit: `3885c4e46513ef0fc81fca100189e54f1714c6ca`
- Archive SHA256: `9451c28c1c592ef5a81b646e805df3e88ed75bfb2c2c9615c8207ac1f0413a9f`
- Unmodified `Java/Concentus/src/main/java` and root `LICENSE`; no reference native binary included.
- Recreate with `git archive --format=zip --output=concentus-3885c4e-java.zip 3885c4e46513ef0fc81fca100189e54f1714c6ca LICENSE Java/Concentus/src/main/java` in the upstream repository.
- core-audio verifies the archive checksum, compiles Java 8 bytecode and consumes the resulting local JAR. No snapshot/JitPack dependency, extra project module or network required for this dependency at build time.
- Original source copyright headers and full LICENSE remain in the archive. The generated JAR carries `META-INF/concentus/LICENSE`; APK license asset is also included for distribution.

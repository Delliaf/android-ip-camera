# Development instructions for this fork

**Always read [DEVELOPMENT_HISTORY.txt](DEVELOPMENT_HISTORY.txt) before changing anything.**
It is the persistent technical handoff and changelog for this project and should
remain usable if this conversation or tool session is no longer available.

For every significant code or CI change:
1. Inspect the current `feature/manual-camera-4k-stream` head and PR #1.
2. Consult recent actual GitHub Actions logs and recorded LG-H872 test results.
3. Keep changes LG-H872 Camera2 main ID0 guarded; leave RTSP/ONVIF intact.
4. Produce **only arm64-v8a native APKs**; avoid 32-bit Rust/NDK targets.
5. Do not assume 16:9: the target accepts 4:3 and 1:1; prefer 4:3 source
   and output to retain native sensor pixels in a 4x GPU crop experiment.
6. Never add proprietary camera APKs, HAL binaries, private logs or tokens.
7. After each milestone, **append to DEVELOPMENT_HISTORY.txt**: date, SHA,
   exact commands / CI run URLs, successes, failures, hardware results,
   unresolved problems, and the next action for another engineer.
8. Do not merge draft PR #1 until user validates results on physical LG G6.

Other documentation: [LG_G6_H872_MAIN0.md](LG_G6_H872_MAIN0.md).

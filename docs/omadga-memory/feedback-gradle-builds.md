---
name: feedback-gradle-builds
description: How to run the heavy OMADga Gradle builds without overloading the machine or losing the client
metadata:
  node_type: memory
  type: feedback
  originSessionId: 616adf7d-c019-4950-99e0-3b681ef1f99d
  modified: 2026-11-14T15:10:01.152Z
---

On the ThinkPad (15 GB RAM) Gradle builds saturate the CPU (load average 50+) and the user had to ask for everything
to be stopped. Timings measured here: cold `assembleDebug` 16 min, full `check` 18 min, `:app:check` about 10 min,
`:core:common:check` 1.5 min, `:domain:check` 1 min.

- Start long builds with `nohup ./gradlew ... > log 2>&1 &` and wait on the log; a foreground call that passes the
  Bash timeout kills the Gradle client while the daemon keeps running.
- Never start a second build while one runs: a busy daemon can't be reused, so a second daemon spawns and memory runs out.
- `pkill -f <pattern>` can kill the Bash tool's own shell (exit 144); kill by PID and use bracket patterns like `[G]radle`.
- Prefer module-scoped checks (`:core:common:check`, `:domain:check`) while iterating; run `:app:check` once before a commit.
- The `t3_background_wait` tool returned early, so poll the log or use a Bash `until grep BUILD` loop in the background.

**Why:** repeated overload and daemon clashes on 2026-11-14.
**How to apply:** use these rules on any low-RAM machine; a desktop with more cores and RAM can relax them.
Related: [[project-omadga-status]].

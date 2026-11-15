# M12 — Instrumented Feed and Reels placement diagnosis

Baseline: HMX Insta+ worktree `ca16a54a7131e599b5267e7260e1e9e1f21ac345`, branch `feature/hmx-downloader-ghost-mode-v2`.

M12 is diagnosis-first. It adds **observation**, not placement changes. The M11 decision code still decides whether and where the control is shown.

## Documents

| Document | What it answers |
|---|---|
| [M12_PHASE1_IMPLEMENTATION_AUDIT.md](M12_PHASE1_IMPLEMENTATION_AUDIT.md) | How M11 actually binds Feed and Reels, including the ViewHolder lifecycle |
| [M12_REJECTION_BRANCH_REGISTER.md](M12_REJECTION_BRANCH_REGISTER.md) | Every branch that can reject or hide the control, with a stable ID |
| [M12_PHASE2_DIAGNOSTIC_INSTRUMENTATION.md](M12_PHASE2_DIAGNOSTIC_INSTRUMENTATION.md) | The new trace schema, the derived state, and the shadow policy comparison |
| [M12_PHASE3_DEVICE_CAPTURE_PROCEDURE.md](M12_PHASE3_DEVICE_CAPTURE_PROCEDURE.md) | The exact OPPO A38 / Android 15 capture, classification and evidence template |
| [M12_PHASE4_PLACEMENT_POLICY.md](M12_PHASE4_PLACEMENT_POLICY.md) | The pure-Java placement policy, its decision tables and its tests |
| [M12_PHASE5_REGRESSION_MATRIX.md](M12_PHASE5_REGRESSION_MATRIX.md) | How every M11 guarantee is preserved and where it is proven |
| [M12_PHASE6_VERIFICATION.md](M12_PHASE6_VERIFICATION.md) | Exactly what was run, with results, and what was not |
| [M12_DEVICE_EVIDENCE_ANALYSIS.md](M12_DEVICE_EVIDENCE_ANALYSIS.md) | The OPPO A38 capture: the exact Reels branch, the repetition explanation, the Feed non-result |
| [M12_FINAL_REPORT.md](M12_FINAL_REPORT.md) | Exact failure branches, evidence status, recommended fixes, limitations |

## Status at the end of this phase

| Item | Status |
|---|---|
| Source audit | Complete |
| Diagnostic instrumentation | Added, compiled, lint-clean |
| Pure-Java placement policy | Added |
| Policy unit tests | Pass (20 groups) |
| JVM downloader/ghost regression tests | Pass |
| Repository, feature and isolation gates | Pass |
| Protected-method self-test and manifest pin | Pass |
| Full extension type-check (`javac` + `android.jar`) | Pass |
| Device capture (Reels) | **Performed** on OPPO A38 / Android 15, M11.1 build. Exact branch identified: `ANCHOR_TYPE`. |
| Device capture (Feed) | **Not captured.** Feed is `NOT CLASSIFIED`. |
| APK build | Reels fix is ready for the gated workflow; no APK was built locally. |
| APK signature / package / patch-report verification | **Not possible without an APK.** |

No claim in this directory treats source tests as device proof. The Reels branch is backed by the device log plus the exact class hierarchy of the installed Instagram version; the Feed state is still unclassified.

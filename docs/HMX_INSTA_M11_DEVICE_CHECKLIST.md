# M11 OPPO A38 acceptance record

Device: OPPO A38 / CPH2579, Android 15 / API 35, arm64-v8a.
Status: **NOT TESTED**. No physical device evidence is available in this session.

Record the installed APK SHA-256, commit, release tag, timestamp, and tester
before testing. Screenshots must be taken from that exact APK.

| Test | Procedure and expected result | Result |
| --- | --- | --- |
| Five Feed posts | Use five distinct posts; verify icon immediately left of Bookmark; confirm each; compare saved file to selected post | NOT TESTED |
| Five Reels | Watch without tapping: zero jobs. For each supported Reel, confirm once and compare saved video and audio with selected Reel | NOT TESTED |
| Placement | Capture Feed and Reels controls in portrait and landscape; verify no overlap and no icon on unsupported layouts | NOT TESTED |
| Recycling | Open confirmation for A, scroll/rebind to B, then confirm; stale request must be rejected | NOT TESTED |
| Rapid switching | Swipe A-B-C-A rapidly and tap; never download B, C, or the first Home feed video | NOT TESTED |
| Carousel | Select individual items and all items explicitly; compare order and identities; repeated confirmation must not duplicate saved/queued items | NOT TESTED |
| Missing/expired | Missing identity or expired URL must enqueue nothing and show a rejection | NOT TESTED |
| Cover + video | Reel with a cover must save the playable video, never the cover or a segment | NOT TESTED |
| OFF/ON | OFF hides controls and blocks confirmation; ON permits explicit requests | NOT TESTED |
| Queue | Verify progress, completion, cancellation, retry, notifications, Gallery publication, and retained history after restart | NOT TESTED |
| Ghost Mode | Toggle master and supported controls; verify existing behavior and settings persistence | NOT TESTED |
| Instagram regression | Verify login/session, Feed, Stories, Reels playback, profiles, messages and navigation | NOT TESTED |

Required evidence: icon placement screenshots, confirmation preview, selected
post/Reel identity, resulting saved file or thumbnail, and queue/history showing
no unrelated jobs. Record FAIL with reproduction steps and redacted request
logs. A static APK check or pure Java test cannot change any row here to PASS.

Reels UI integration is currently blocked on establishing a safe native anchor.
The baseline layout resource maps to Instagram's packed layout format rather
than a standard XML entry; no live layout hierarchy has been captured. Do not
use absolute screen coordinates as a substitute or mark Reels acceptance PASS.

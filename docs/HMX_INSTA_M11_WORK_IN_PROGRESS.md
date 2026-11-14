# M11 work in progress

M11 is not release-ready. No M11 APK or device verification is claimed.

## Corrected feed anchor investigation

The previously proposed `LX/0aVY.A0e` anchor is incorrect for the feed action
row. Its unique string `Media Thumbnail %s Cell` belongs to
`media_thumbnail_preview_item_layout` (resource `0x7f0e0fce`). Its holder
contains `preview_media_actions_view`, not the feed bookmark action row.
Do not install the proposed hook there.

In baseline 454, `LX/02x5.<init>(View)` loads both `row_feed_button_like`
and `row_feed_button_save`. The holder is bound by
`LX/07u7.A08(LX/00q7;LX/0JAK;LX/02zv;LX/02x5;)V`.
The `LX/02zv` parameter contains a typed Instagram Media field. This is a
candidate for the actual feed action-row hook, pending structural fingerprint
and protected-method enforcement. These obfuscated descriptors are analysis
evidence, not stable patch anchors.

## Implemented locally

- Removed the global latest-observation overlay.
- Added bound request evaluation with required media ID and rejection of
  common cover, manifest, and streaming-segment URL forms.
- Added a conservative feed binder adapter for horizontal LinearLayout rows
  containing the native bookmark; unsupported layouts receive no icon.
- Converted the confirmation sheet to bound requests, explicit carousel item
  selection, confirmation-time expiry/switch validation, and request logging.
- Feed model snapshots are reread at tap time rather than cached across taps.

## Verification

- Plain Java downloader and Ghost Mode regression suites: PASS.
- New bound-request regression: PASS.
- Full extension javac compilation against Android 35: PASS (standard
  source/target and deprecation notices).
- Patch bundle compilation, protected APK comparison, gated APK build: pending.
- OPPO A38 device testing and screenshots: NOT TESTED.

## Remaining integration

The patch now resolves the native feed action binder using its `itas-android`
trace string, four-object signature, typed Media model field, and native
bouncy-button holder fields. It inserts a single `invoke-static/range {p3 .. p4}`
to `DownloaderBinder.bindFeed(model, holder)` at entry. Runtime binding supports
only horizontal LTR LinearLayout parents of the actual native bookmark.
Rebinds and detaches invalidate pending confirmation generations, including
A-B-A recycling. These checks have not yet been exercised on an Android device.

The protected verifier now normalizes only this exact prefix invocation and
its three-code-unit address shift before class equality. Adversarial Python
checks pass for wrong operands, placement, and changed original instructions.
The source isolation check preserves the M10 aggregate over unchanged protected
files and separately pins the three explicit M11 hook/verifier files in
`scripts/ci/m11_reviewed_sources.sha256`. The lightweight isolation check passes.

Reels pager/control integration is pending. The confirmation sheet has textual type information; visual preview
and current-carousel-position discovery remain pending. Request logging still
needs a complete redaction audit and correlation IDs. No remote CI or APK run
has yet completed for this implementation checkpoint. GitHub token access and
the active CI/APK workflows were verified without exposing the token.

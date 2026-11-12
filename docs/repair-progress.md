# Reliability review repair queue â€” 2026-11-10

Owner direction: repair one item at a time, preserve Git history and validation evidence.
Each item uses an isolated branch and PR. The owner authorized merging repairs on green CI;
merge and installation remain separate milestones. Feature PRs remain unmerged until native
acceptance. No version bump or production deployment is part of this repair queue.

## Verified baseline

- Phone: signed Voxdroid 0.3.0, code 5, APK matches published SHA256; basic dictation passes.
- Desktop: byovox narration branch 5745834; separate Ctrl+Shift+Z dictation passes,
  Ctrl+Alt+R narration fails in Notepad before TTS (no selection state).
- Voxdroid narration 502d129: isolated handset checks silently cancel before completion;
  exact cancellation source remains unproven. Test app removed, production service restored.
- Passing CI did not establish runtime acceptance.

## Ordered work

1. **Merged: incomplete cleanup responses (both apps).** Reject explicit non-stop
   finish reasons, preserve the full raw transcript through existing fallback and notice.
   Keep compatibility with existing servers that omit finish_reason. Explicit null is not
   a completed response. Tests must fail before the parser fix and pass afterwards.
2. **Merged:** cancel active Android dictation transport so new takes do not wait behind it (#51).
3. **Repaired on the desktop feature branch:** separate byovox shortcuts, asynchronous selection,
   Notepad provider lifecycle and guarded Copy fallback. Owner confirmed narration/Stop in the
   tested apps. Desktop #50 adds no-voice feedback; the owner confirmed muted Fifine notice and
   unmuted dictation on b09dbdb. Broader desktop native acceptance remains tracked in #33/#42.
4. **In progress (#55):** fix Android narration event cancellation and automatic screen timeout.
5. Queued: restore an accessible tap trigger on Android's own Try screen.
6. Queued: reconcile Android TTS endpoint validation and cancellation with security policy;
   keep security reproduction details local until reviewed.
7. Queued: reassess documented production error-body logging.

## Item 1 contract

A successful HTTP response may still contain partial output. If the first choice includes
finish_reason, only the string stop is accepted. Other values (including length, filtering,
tool calls, unknown strings, null and malformed types) produce a fixed content-free error.
Missing finish_reason preserves the existing compatible-server behavior. This cannot detect
an endpoint silently truncating output while omitting or misreporting its completion reason.
The existing output token cap and cleanup prompt remain unchanged.

## Validation history

- Regression cases added for incomplete and normal stop responses using loopback servers.
- Red/green results and PR links will be recorded before handoff.

Tracking: https://github.com/iteramen/voxdroid/issues/48; paired app: https://github.com/iteramen/byovox/issues/35.

- RED: loopback returned partial content with finish_reason=length; old parser accepted it.
- Regression commit precedes implementation in this branch.

- GREEN (Android): lint, full JVM suite (468 tests, 0 failures), debug APK assembly and 6 handset-helper tests passed.
- Implementation complete locally; CI and PR review pending. Production phone is unchanged.
- Desktop counterpart PR: https://github.com/iteramen/byovox/pull/36

## Item 1 landing

Owner authorized merge-on-green. Verified heads 17e9787 (Android) and 4392e06
(desktop) passed all CI. Squash merges: Voxdroid 46c97b6 / PR #49, byovox
760b5e0 / PR #36. Issues #48 / #35 closed; local main checkouts fast-forwarded.
Installed applications remain the previously tested versions.

## Item 2 contract

Cancellation/supersession must disconnect active dictation STT and cleanup requests
without blocking the main thread, free the serial worker for the next take, and
suppress retry/late results. Keep the existing total request timeout. Test both
waiting for response headers and blocked response-body reads with a real loopback
server. Already-transmitted requests cannot be recalled from the remote server.

- Item 2 tracking: https://github.com/iteramen/voxdroid/issues/50
- RED: stalled STT request retained the worker; next-request regression timed out.
- Platform distinction: desktop JVM disconnect can block during body reads; this behavior
  is not Android's. JVM tests retain real stalled-header coverage for both STT and cleanup.
- Android production-transport probe passed headers (52 ms) and body (53 ms), including
  completion of the next serial request. No APK installation or settings change was needed.
- Full local gates passed: lint, 471 JVM tests, debug assembly, and 6 helper tests.
- Final committed-APK native probe and CI checkpoint will be attached to the PR.

## Item 4 contract and reproduction

Source content changes currently cancel even an unchanged selected paragraph. Regression cf28502
extracts the existing event policy unchanged and fails two tests: unrelated content interrupts
speech, while a missing/changed chosen node without a source event is not checked.

The repair keeps picker invalidation conservative; during speech it refreshes only the chosen
node and requires unchanged text/bounds, package/window, visibility and non-password/read-only
attributes. Root change, scroll, source window-state change and all existing stop paths remain
cancellation. A glyph window flag prevents automatic timeout during synthesis/playback and is
cleared when active work ends. Native checks are listed in docs/testing.md; no production APK,
configuration or credentials are replaced. Parent #47 remains draft.

Local gates: lint, 487 JVM tests (486 passed, one explicit live-server test skipped), debug APK
assembly and all six handset-helper tests passed. ADB currently has no connected device;
handset acceptance remains pending and must not be inferred from these automated gates.
The three blocking workflows previously filtered PRs to main only. Their PR filters now also
include feat/read-aloud, so stacked repairs run the same gates, dependency audit and unsigned
release witness before merge. Push/release triggers and checks are unchanged.

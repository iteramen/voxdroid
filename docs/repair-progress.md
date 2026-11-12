# Reliability review repair queue â€” 2026-11-10

Owner direction: repair one item at a time, preserve Git history and validation evidence.
Each item uses an isolated branch and PR. The owner authorized merging repairs on green CI;
merge and installation remain separate milestones. Feature PRs remain unmerged until native
acceptance. After the repair queue and final acceptance, proceed to the authorized version bump,
signed release and in-place phone upgrade with configuration preserved. Live TODO: issue #57.

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
4. **Merged and core handset checks passed (#55 / #56):** Android narration source/power lifecycle.
   Native a9eac33 evidence is linked from #55; remaining combined acceptance is listed below.
5. **Merged (#58 / #59):** microphone-only Try control; own settings hidden on the handset.
   Permission revocation closes the test task; final real start/stop remains in combined acceptance.
6. **Merged (#60 / #61):** TTS endpoint validation and cancellation; native headers/body stalls
   cancelled in 56/86 ms and the next serial request completed. Security contract reconciled.
7. **Active (#62):** remove sensitive server-error detail from production logging.
8. Final combined regression on the release candidate: normal-speed English narration, dictation/
   captions coexistence, interruption/source guards, consent and settings preservation. Carry
   forward explicitly applicable evidence; broader device/language coverage is not an endless
   release gate.
9. Merge feature on green, bump, build/sign and upgrade the phone preserving configuration after
   acceptance. Play Console #5 remains owner-parked; #2 is untouched. Other backlog is outside
   this audit wave (including #44/PR #45); do not silently include or close it.

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
assembly and all six handset-helper tests passed. PR #56 merged as 0b1021b after all three
CI checks passed on a9eac33. Handset evidence then passed: unrelated updates continued to real
TTS completion; changed/removed text and picker updates cancelled; speaker Stop, Home, scroll
and same-app navigation cancelled; HTTP 503 released the screen flag; 15-second automatic
timeout was prevented during delayed synthesis and playback; injected screen-off stopped and
hid the glyph. The microphone stayed ungranted, no production credentials/config were copied,
the test package was removed, and original settings were restored/read back. Power tests used
silent PCM after the owner objected to the first slow synthetic speech sample. Use normal speed
for audible checks. Remaining qualification is tracked under final combined regression, not
as a claim that these observed passes are still pending.
The three blocking workflows previously filtered PRs to main only. Their PR filters now also
include feat/read-aloud, so stacked repairs run the same gates, dependency audit and unsigned
release witness before merge. Push/release triggers and checks are unchanged.

## Item 5: own Try-screen tap control (#58)

Regression 15cbb5a fails because the old own-app exclusion hides every control in TryActivity.
The repair allows microphone-only controls when the own editable try_field is input-focused;
settings/credentials, password focus and lock remain hidden. Narration remains unavailable in
own windows; switching to an external app rebuilds its usual controls. The existing trigger
toggle and microphone permission check remain in force. Native UI qualification follows the
committed isolated APK; no production data is copied.

## Item 6: TTS endpoint and transport policy (#60)

Regression a301cbd fails all four synthetic cases before implementation: malformed endpoint
policy, transport-boundary validation, cancellation at connection registration, and cancellation
during a stalled response-header wait. The fix validates endpoint structure at both preparation
and transport, checks generation before upload, and interrupts obsolete I/O from the watchdog
rather than blocking the main thread. The total deadline prevents later I/O phases restarting
after expiry. Android header/body behavior is qualified with tools/TtsCancellationProbe.java;
no installed-app data, microphone, credentials or external endpoint is used by that probe.


## Item 5 and 6 landing evidence

PR #59 merged as a57a7ca after green CI on f483bc1. The native Try screen displayed the microphone
and no narration control; own settings hid the control. Actual start/stop remains a final manual
acceptance check. Temporary permissions and the test package were removed.
PR #61 merged as 8e3cd4d after green CI on 7c4634e. The committed APK transport passed Android
loopback header/body cancellation in 56/86 ms with the next serial request completing. No app
installation, credentials or microphone were used for this probe.

## Item 7: content-free failure diagnostics (#62)

Regression 56ab985 demonstrates server content and unrelated credentials escaping through STT,
cleanup and transport failures. Error details now omit response bodies entirely and classify
exceptions by type. Unknown adapter errors cannot turn raw exception messages into trusted
stage errors. HTTP status, stage, body-size refusal and cleanup's original-text fallback remain.
Security/privacy claims now apply to debug failure logs too. Synthetic tests use no real data.

Local item 7 gates passed: lint, 501 JVM tests (500 passed, one explicit live-server skip),
debug APK assembly and all six synthetic handset-helper tests. Red/green regression and source
review cover both service failure-log paths; no production log or private content was collected.

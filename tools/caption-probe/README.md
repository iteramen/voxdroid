# #29 isolated handset probe (not shipped)
Build: python3 tools/caption-probe/build.py on ourobeast.
Package io.github.iteramen.voxdroid.probe. No network/microphone/storage/a11y permission.
SYSTEM_ALERT_WINDOW only; panel NOT_FOCUSABLE, manual Close before navigating; no origin guard.
Share exactly one non-sensitive fixture; no Send/composer operations.
Initial stream read (max32MiB/30sec), SHA256 and media metadata while Activity open.
Finish Activity, wait2seconds, reopen stream from private Service. URI passed as plain String extra,
without ClipData/data/grant flags, to avoid intentionally prolonging original Activity grant.
Service keeps process alive; not a production/background capture flow.
reports.jsonl app-private: timestamps, authority (no path), MIME/size/hash/duration/sample-rate,
original intent flags and exception class names only. No audio/text/chat/filename/URI retained.
No backups. No STT. Never replaces installed Voxdroid. Exact ADB commands approved per session.

V2: MediaExtractor identifies the sole audio track; only audio/opus is decoded via platform MediaCodec to PCM16. Counts/discards output; records codec, rate, channels, frames and EOS. Decode loop deadline 30s, 8MiB per buffer, 8–48kHz, 1–2 channels, five-minute decoded-frame ceiling. These are probe engineering limits, not new product defaults. No resampling, PCM hashing, retention or playback. Native extractor setup is not process-isolated by this probe; this is not a proof of production malformed-input watchdog behavior. V2 panel scrolls metadata at a fixed 600px height, keeping Close visible.

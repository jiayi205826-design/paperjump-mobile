# PaperJump Android prototype

This is an isolated Android camera and local-anchor recognition prototype.
It reads the existing AES-GCM encrypted binding snapshot from Paper Portal
after pairing through a private `paperjump://pair?sync=...` QR code. The key is
never stored in this repository or the APK.

The first build verifies local anchors only. Hand pointing and full-page
natural recognition still need a device benchmark and implementation.

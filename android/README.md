# PaperJump Android prototype

This is an isolated Android camera and local-anchor recognition prototype.
It reads the existing AES-GCM encrypted binding snapshot from Paper Portal
after pairing through a private `paperjump://pair?sync=...` QR code. The key is
never stored in this repository or the APK.

When the paired phone and desktop are on the same Wi-Fi, the phone announces
itself and streams AES-GCM encrypted camera frames to the desktop. The desktop
PaperJump keeps all recognition, binding, and link-opening logic. The app must
be open in the foreground for Android to allow camera use. USB is not needed.

Phone-only recognition remains experimental. Desktop-over-Wi-Fi is the main
path until device benchmarks establish phone-only parity.

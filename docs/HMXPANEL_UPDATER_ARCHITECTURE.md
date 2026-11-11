# HMXPanel — Updater Architecture

The canonical updater document is
**[HMX_INSTA_UPDATER.md](HMX_INSTA_UPDATER.md)**.

It records the removal of the upstream FeurStagram updater (release polling,
"Update available" popup, APK download, `PackageInstaller` install path), the
disabled seam that replaces it, and the rules a future HMXPanel updater must
satisfy — verified releases only, SHA-256 and certificate verification, explicit
user confirmation, and no automatic install and no background polling.

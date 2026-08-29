Tree TXT Android
================
Select a folder using Android's Storage Access Framework, recursively scan it, and
stream a Unicode Linux-style tree into tree.txt. Designed for 20k+ entries and deep
nesting. It does not read file contents.

v1.1 changes:
- Scanning now runs in a foreground service, so it keeps going if you switch
  apps or lock the screen. A persistent notification shows live progress.
- Progress bar + live "entries/sec" and elapsed-time stats while scanning.
- Fixed a crash ("Invalid URI") that hit every scan at the root folder.
- Cleaner UI (styled buttons, spacing, status/log area).
- Save works even if you reopen the app after the scan finished in the background.

Build:
  gradle assembleDebug
or use Codemagic with codemagic.yaml, or GitHub Actions (.github/workflows/build.yml).

The app cannot bypass Secure Folder isolation; install it inside Secure Folder and
select the target folder there if Motorola exposes it to the Android document picker.

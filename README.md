Tree TXT Android
================
Select a folder using Android's Storage Access Framework, recursively scan it, and stream a Unicode Linux-style tree into tree.txt. Designed for 20k+ entries and deep nesting. It does not read file contents.

Build:
  gradle assembleDebug
or use Codemagic with codemagic.yaml.
The app cannot bypass Secure Folder isolation; install it inside Secure Folder and select the target folder there if Motorola exposes it to the Android document picker.

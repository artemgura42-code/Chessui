# Chess Vision (Android)

1. Open this folder in Android Studio (it creates the Gradle wrapper). Build & run on a phone (Android 10+).
2. Put a Stockfish 19 Android arm64 binary at `app/src/main/jniLibs/arm64-v8a/libstockfish.so`
   (official release page, or build from source with the NDK: `make -j build ARCH=armv8-dotprod COMP=ndk`).
   The NNUE net is embedded in official builds.
3. Start the app -> "Начать захват экрана" -> allow overlay + screen capture -> open Lichess / Chess.com -> start a game vs a bot
   from the initial position (the app learns the pieces from the starting position; colour at the bottom = your colour).

Not compiled or tested yet. Tune `EN` in BoardVision.kt if empty squares are seen as occupied (or the reverse).
Known limits: flat board themes only (no wood textures); do not flip the board mid-game; start capture before the game starts.
Stockfish is GPLv3: fine for personal use, a store release requires publishing the source.


## Stockfish integration in the GitHub build

The Android workflow downloads the official Stockfish 19 ARM64 universal archive
from the official Stockfish GitHub release at build time, extracts its executable,
places it at `app/src/main/jniLibs/arm64-v8a/libstockfish.so`, and packages it
into the debug APK. The engine is not committed to this repository.
The floating diagnostic panel reports board detection, initial-position learning,
FEN, and Stockfish response. The recognition algorithm still requires the starting
position to learn piece templates and currently assumes a flat checkerboard theme.

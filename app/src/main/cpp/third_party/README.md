Place whisper.cpp sources here if you want to build on‑device Whisper.

Expected (minimum) files from upstream whisper.cpp project:
- whisper.cpp sources and headers
- ggml sources (e.g., ggml.c and related files used by your version)

You can add as a git submodule or copy files:

  git submodule add https://github.com/ggerganov/whisper.cpp app/src/main/cpp/third_party/whisper.cpp

Then enable CMake option USE_WHISPERCPP=ON in your build.

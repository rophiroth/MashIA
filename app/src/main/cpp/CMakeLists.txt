cmake_minimum_required(VERSION 3.18.1)

project(mashwhisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Debug JNI builds are often too slow for Whisper; force optimization even in Debug.
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O3)
endif()

# Option: build with whisper.cpp sources if available locally
option(USE_WHISPERCPP "Build JNI against whisper.cpp" ON)

add_library(mashwhisper SHARED WhisperBridge.cpp)

target_include_directories(mashwhisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if (USE_WHISPERCPP)
    # Expected layout: app/src/main/cpp/third_party/whisper.cpp
    set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp)
    if (EXISTS ${WHISPER_DIR}/CMakeLists.txt)
        # Disable OpenMP in GGML for Android to avoid libomp dependency
        set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on Android" FORCE)
        add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper-bld)
        target_compile_definitions(mashwhisper PRIVATE USE_WHISPERCPP=1)
        # Link NDK C++ shared runtime explicitly to both our JNI and the upstream shared lib
        find_library(CPP_SHARED c++_shared)
        if (TARGET whisper)
            target_link_libraries(whisper PUBLIC ${CPP_SHARED})
        endif()
        if (TARGET ggml-base)
            target_link_libraries(ggml-base PUBLIC ${CPP_SHARED})
        endif()
        if (TARGET ggml)
            target_link_libraries(ggml PUBLIC ${CPP_SHARED})
        endif()
        if (TARGET ggml-cpu)
            target_link_libraries(ggml-cpu PUBLIC ${CPP_SHARED})
        endif()
        target_link_libraries(mashwhisper whisper log ${CPP_SHARED})
    else()
        message(WARNING "USE_WHISPERCPP=ON but whisper.cpp not found at ${WHISPER_DIR}")
        target_link_libraries(mashwhisper log)
    endif()
else()
    find_library(CPP_SHARED c++_shared)
    target_link_libraries(mashwhisper log ${CPP_SHARED})
endif()

cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llama CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# llama.cpp pinned to tag b4458 (verified API era used by llama_jni.cpp).
# Integrity: official GitHub source tarball, hash pinned below.
FetchContent_Declare(
    llamacpp
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b4458.tar.gz
    URL_HASH SHA256=62e698f1a34e851d448db0d85123bf6f1c3f6eec5a25e29ddc8a4a71aeda5230
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(llamacpp)

add_library(jarvis_llama SHARED llama_jni.cpp)

find_library(android_log log)
target_link_libraries(jarvis_llama PRIVATE llama ${android_log})
target_compile_options(jarvis_llama PRIVATE -O3 -fexceptions -frtti -Wall)
target_include_directories(jarvis_llama PRIVATE ${llamacpp_SOURCE_DIR}/include)

# MilkDrop for the visualizer: libprojectM (LGPL-2.1, linked as its own shared library, unmodified) plus a small
# JNI bridge. Built for the tablet's 64-bit ARM only; on other ABIs the bridge is a stub that reports MilkDrop
# as unavailable, so the app still installs and the look falls back to smooth bars.
cmake_minimum_required(VERSION 3.22.1)
project(jeepdash_milkdrop C CXX)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    include(FetchContent)
    # projectM is always built optimised, even in the debug app: a debug build runs far too slowly
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
    set(CMAKE_C_FLAGS_DEBUG "-O2 -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS_DEBUG "-O2 -DNDEBUG" CACHE STRING "" FORCE)
    set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
    set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
    set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
    set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    # pinned commits; the evaluator is projectM's one submodule, so it is fetched alongside and put in place
    FetchContent_Declare(pmeval
            URL https://github.com/projectM-visualizer/projectm-eval/archive/22fb0cfd8f2dfbcd2b68f2443e7f44e19b32c09a.zip)
    FetchContent_Declare(projectm
            URL https://github.com/projectM-visualizer/projectm/archive/9e26f5b5bf89b015f197a16a61bcb21f4946d985.zip)
    FetchContent_GetProperties(pmeval)
    if(NOT pmeval_POPULATED)
        FetchContent_Populate(pmeval)
    endif()
    FetchContent_GetProperties(projectm)
    if(NOT projectm_POPULATED)
        FetchContent_Populate(projectm)
    endif()
    file(COPY ${pmeval_SOURCE_DIR}/ DESTINATION ${projectm_SOURCE_DIR}/vendor/projectm-eval)
    add_subdirectory(${projectm_SOURCE_DIR} ${projectm_BINARY_DIR} EXCLUDE_FROM_ALL)

    add_library(milkdrop SHARED milkdrop_jni.cpp)
    target_compile_definitions(milkdrop PRIVATE HAVE_PROJECTM=1)
    target_link_libraries(milkdrop PRIVATE libprojectM::projectM EGL GLESv3 log dl)
else()
    add_library(milkdrop SHARED milkdrop_jni.cpp)
    target_link_libraries(milkdrop PRIVATE log)
endif()

#!/usr/bin/env python3
"""Idempotent tweaks to the NedoLM-patched llama.cpp src/CMakeLists.txt.

1. Make NEDO004_FFI_LIB overridable (-D...) so one tree serves Windows/Android/Linux builds.
2. Link the Rust staticlib against the right platform runtime libraries.
"""
import sys

path = sys.argv[1]
s = open(path, encoding="utf-8").read()
orig = s

old = 'set(NEDO004_FFI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../vendor/nedo004-ffi/target/release/libnedo004_ffi.a")'
new = '''if (NOT NEDO004_FFI_LIB)
    set(NEDO004_FFI_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../vendor/nedo004-ffi/target/release/libnedo004_ffi.a")
endif()'''
s = s.replace(old, new)

old2 = "    target_link_libraries(llama PRIVATE nedo004_ffi dl pthread m)"
new2 = """    if (WIN32)
        target_link_libraries(llama PRIVATE nedo004_ffi ws2_32 userenv bcrypt ntdll)
    elseif (ANDROID)
        target_link_libraries(llama PRIVATE nedo004_ffi dl m)
    else()
        target_link_libraries(llama PRIVATE nedo004_ffi dl pthread m)
    endif()"""
s = s.replace(old2, new2)

if s != orig:
    open(path, "w", encoding="utf-8", newline="\n").write(s)
    print("patched", path)
else:
    print("already patched", path)

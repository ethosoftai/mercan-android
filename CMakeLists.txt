# Mercan apps: shared engine + Windows GUI + CLI, on top of the patched llama.cpp.
# BUILD_SHARED_LIBS + GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS gives one CPU backend
# DLL/.so per ISA level; ggml picks the best one for the host CPU at runtime.
cmake_minimum_required(VERSION 3.16)
project(mercan_apps VERSION 0.5.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(MERCAN_REPO_DIR  "${CMAKE_SOURCE_DIR}/../repo" CACHE PATH "Mercan repository")
set(MERCAN_LLAMA_DIR "${MERCAN_REPO_DIR}/build/_deps/llama-src" CACHE PATH "Patched llama.cpp")
option(MERCAN_BUILD_GUI "Build the Windows GUI" ON)
option(MERCAN_BUILD_CLI "Build the mercan CLI" ON)
option(MERCAN_BUILD_JNI "Build the Android JNI library" OFF)
option(MERCAN_BUILD_TEST "Build the headless chat smoke test" OFF)
option(MERCAN_BUILD_SERVER "Build the OpenAI-compatible mercan-server" OFF)

if(NOT EXISTS "${MERCAN_LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Patched llama.cpp not found at ${MERCAN_LLAMA_DIR}")
endif()

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory("${MERCAN_LLAMA_DIR}" "${CMAKE_BINARY_DIR}/llama")

set(MERCAN_RUNTIME_SOURCES
    ${MERCAN_REPO_DIR}/runtime/libmercan/src/mercan.cpp
    ${MERCAN_REPO_DIR}/core/model_loader.cpp
    ${MERCAN_REPO_DIR}/core/generic_backend.cpp
    ${MERCAN_REPO_DIR}/core/architecture_registry.cpp
    ${MERCAN_REPO_DIR}/core/tokenizer_registry.cpp
    ${MERCAN_REPO_DIR}/core/plugin_loader.cpp
    ${MERCAN_REPO_DIR}/core/builtin_plugins.cpp
    ${MERCAN_REPO_DIR}/architectures/nedolm/architecture.cpp
    ${MERCAN_REPO_DIR}/architectures/nedolm/tokenizer.cpp
)

add_library(libmercan STATIC ${MERCAN_RUNTIME_SOURCES})
set_target_properties(libmercan PROPERTIES OUTPUT_NAME mercan)
target_include_directories(libmercan
    PUBLIC  "${MERCAN_REPO_DIR}/runtime/libmercan/include"
    PRIVATE "${MERCAN_REPO_DIR}/core" "${MERCAN_LLAMA_DIR}/include" "${MERCAN_LLAMA_DIR}/ggml/include")
target_link_libraries(libmercan PUBLIC llama ggml ${CMAKE_DL_LIBS})

add_library(mercan_chat STATIC common/mercan_chat.cpp)
target_include_directories(mercan_chat PUBLIC common)
target_link_libraries(mercan_chat PUBLIC libmercan)

if(MERCAN_BUILD_CLI)
    add_executable(mercan ${MERCAN_REPO_DIR}/cli/main.cpp)
    target_link_libraries(mercan PRIVATE libmercan)
endif()

# OpenAI-compatible HTTP server (used by the Open WebUI based desktop app).
if(MERCAN_BUILD_SERVER)
    find_package(Threads REQUIRED)
    add_library(mercan_httplib STATIC "${MERCAN_LLAMA_DIR}/vendor/cpp-httplib/httplib.cpp")
    target_include_directories(mercan_httplib PUBLIC "${MERCAN_LLAMA_DIR}/vendor/cpp-httplib")
    target_compile_definitions(mercan_httplib PUBLIC CPPHTTPLIB_TCP_NODELAY=1)
    target_compile_options(mercan_httplib PRIVATE -w)
    target_link_libraries(mercan_httplib PUBLIC Threads::Threads)
    if(WIN32)
        target_compile_definitions(mercan_httplib PUBLIC _WIN32_WINNT=0x0A00)
        target_link_libraries(mercan_httplib PUBLIC ws2_32)
    endif()

    add_library(mercan_server STATIC server/mercan_server.cpp)
    target_include_directories(mercan_server PUBLIC server "${MERCAN_LLAMA_DIR}/vendor")
    target_link_libraries(mercan_server PUBLIC mercan_chat mercan_httplib)

    add_executable(mercan-server server/server_main.cpp)
    target_link_libraries(mercan-server PRIVATE mercan_server)
endif()

# Desktop launcher: WebView2 window + in-process MercanServer + bundled Open WebUI backend.
if(MERCAN_BUILD_GUI AND WIN32)
    set(WEBVIEW_INCLUDE_DIR  "/root/vendor/webview/core/include" CACHE PATH "webview/webview core/include")
    set(WEBVIEW2_INCLUDE_DIR "/root/vendor/webview2/build/native/include" CACHE PATH "Microsoft.Web.WebView2 build/native/include")
    add_executable(Mercan WIN32 windows/main.cpp windows/model_crypto.cpp windows/app.rc)
    target_include_directories(Mercan PRIVATE "${WEBVIEW_INCLUDE_DIR}" "${WEBVIEW2_INCLUDE_DIR}")
    target_link_libraries(Mercan PRIVATE mercan_server wininet shell32 shlwapi ole32 advapi32 version user32 gdi32 bcrypt crypt32 ws2_32 dwmapi)
    target_compile_definitions(Mercan PRIVATE _WIN32_WINNT=0x0A00 WINVER=0x0A00 NTDDI_VERSION=0x0A000000)
    target_link_options(Mercan PRIVATE -municode -mwindows)
endif()

if(MERCAN_BUILD_TEST)
    add_executable(chat_test common/chat_test.cpp)
    target_link_libraries(chat_test PRIVATE mercan_chat)
    add_executable(restore_test common/restore_test.cpp)
    target_link_libraries(restore_test PRIVATE mercan_chat)
endif()

if(MERCAN_BUILD_JNI)
    add_library(mercan_jni SHARED android/app/src/main/cpp/mercan_jni.cpp)
    target_include_directories(mercan_jni PRIVATE "${MERCAN_LLAMA_DIR}/ggml/include")
    target_link_libraries(mercan_jni PRIVATE mercan_chat log android)
endif()

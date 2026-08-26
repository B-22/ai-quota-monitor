cmake_minimum_required(VERSION 3.21)

set(CODEX_USAGE_VERSION "0.6.0" CACHE STRING "AIUsage plugin version")

project(AIUsage VERSION "${CODEX_USAGE_VERSION}" LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Use static MSVC runtime
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/src/CodexUsageVersion.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/generated/CodexUsageVersion.h"
    @ONLY
)

set(TRAFFICMONITOR_INCLUDE_DIR "" CACHE PATH "Directory containing TrafficMonitor PluginInterface.h")

if(NOT TRAFFICMONITOR_INCLUDE_DIR)
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/include/PluginInterface.h")
        set(TRAFFICMONITOR_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")
    elseif(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/TrafficMonitor/include/PluginInterface.h")
        set(TRAFFICMONITOR_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/TrafficMonitor/include")
    elseif(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../TrafficMonitor/include/PluginInterface.h")
        set(TRAFFICMONITOR_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../TrafficMonitor/include")
    else()
        message(FATAL_ERROR "PluginInterface.h not found. Set TRAFFICMONITOR_INCLUDE_DIR or place it in include/.")
    endif()
endif()

message(STATUS "PluginInterface.h dir: ${TRAFFICMONITOR_INCLUDE_DIR}")

# Static library: shared core (JSON, Codex parsing)
add_library(AIUsageCore STATIC
    src/CodexUsageCore.cpp
    src/ClaudeUsageExport.cpp
    src/ClaudeUsageParser.cpp
    src/JsonLite.cpp
)

target_include_directories(AIUsageCore PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
)

target_compile_definitions(AIUsageCore PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

if(MSVC)
    target_compile_options(AIUsageCore PRIVATE /utf-8)
endif()

# DLL: plugin + Claude/Codex/Antigravity/Kiro/OpenCode-Go/OpenRouter fetchers + DPAPI + credential reader
add_library(AIUsage SHARED
    src/CodexUsagePlugin.cpp
    src/DashboardRenderer.cpp
    src/CodexUsageFetcher.cpp
    src/ClaudeUsageFetcher.cpp
    src/ClaudeCredentialReader.cpp
    src/DpapiHelper.cpp
    src/ProxyHelper.cpp
    src/AntigravityUsageFetcher.cpp
    src/KiroCreditsFetcher.cpp
    src/OpenCodeGoUsageFetcher.cpp
    src/OpenRouterUsageFetcher.cpp
    src/AgentStatusWatcher.cpp
    src/AgentLightItems.cpp
    third_party/sqlite/sqlite3.c
)

target_include_directories(AIUsage PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/sqlite"
    "${TRAFFICMONITOR_INCLUDE_DIR}"
)

target_compile_definitions(AIUsage PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

if(MSVC)
    target_compile_options(AIUsage PRIVATE /utf-8)
endif()

target_link_libraries(AIUsage PRIVATE
    AIUsageCore
    shlwapi
    winhttp
    ws2_32
    crypt32
    bcrypt
    gdiplus
    d2d1
    dwrite
)

set_target_properties(AIUsage PROPERTIES
    PREFIX ""
    OUTPUT_NAME "AIUsagePreview"
)

# Tests
add_executable(CodexUsageTests
    tests/CodexUsageCoreTests.cpp
)

target_link_libraries(CodexUsageTests PRIVATE AIUsageCore)

if(MSVC)
    target_compile_options(CodexUsageTests PRIVATE /utf-8)
endif()

enable_testing()
add_test(NAME CodexUsageTests COMMAND CodexUsageTests)

# Cowork / Code indicator-light state machine. Filesystem-free by design:
# the aggregation rule and the two log parsers are pure functions.
add_executable(AgentStatusTests
    tests/AgentStatusTests.cpp
    src/AgentStatusWatcher.cpp
)

target_include_directories(AgentStatusTests PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
)

target_compile_definitions(AgentStatusTests PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

target_link_libraries(AgentStatusTests PRIVATE AIUsageCore)

if(MSVC)
    target_compile_options(AgentStatusTests PRIVATE /utf-8)
endif()

add_test(NAME AgentStatusTests COMMAND AgentStatusTests)

# Geometry of the indicator-light group. Needs the renderer, so it links the
# same Direct2D / GDI+ stack the plugin does.
add_executable(AgentLightTests
    tests/AgentLightTests.cpp
    src/AgentLightItems.cpp
    src/AgentStatusWatcher.cpp
    src/DashboardRenderer.cpp
)

target_include_directories(AgentLightTests PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${TRAFFICMONITOR_INCLUDE_DIR}"
)

target_compile_definitions(AgentLightTests PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

target_link_libraries(AgentLightTests PRIVATE
    AIUsageCore
    gdiplus
    d2d1
    dwrite
)

if(MSVC)
    target_compile_options(AgentLightTests PRIVATE /utf-8)
endif()

add_test(NAME AgentLightTests COMMAND AgentLightTests)

# Host-level checks against the real DLL: the display-item count that decides
# the taskbar slot height, and reported-vs-painted width.
add_executable(PluginLayoutTests
    tests/PluginLayoutTests.cpp
)

target_include_directories(PluginLayoutTests PRIVATE
    "${TRAFFICMONITOR_INCLUDE_DIR}"
)

target_compile_definitions(PluginLayoutTests PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

if(MSVC)
    target_compile_options(PluginLayoutTests PRIVATE /utf-8)
endif()

# The test loads the plugin at runtime, so the DLL has to exist first.
add_dependencies(PluginLayoutTests AIUsage)

add_test(NAME PluginLayoutTests
    COMMAND PluginLayoutTests "$<TARGET_FILE:AIUsage>")

# OpenRouter payload parsing and the UTC quota window. Both are pure, so
# the test needs no network: the JSON below is captured from live
# responses. ProxyHelper comes along because the fetcher links against
# OpenHttpSession even on the paths the test never calls.
add_executable(OpenRouterTests
    tests/OpenRouterTests.cpp
    src/OpenRouterUsageFetcher.cpp
    src/ProxyHelper.cpp
)

target_include_directories(OpenRouterTests PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
)

target_compile_definitions(OpenRouterTests PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

target_link_libraries(OpenRouterTests PRIVATE
    AIUsageCore
    winhttp
    ws2_32
    shlwapi
    crypt32
)

if(MSVC)
    target_compile_options(OpenRouterTests PRIVATE /utf-8)
endif()

add_test(NAME OpenRouterTests COMMAND OpenRouterTests)

add_executable(ProxyHelperTests
    tests/ProxyHelperTests.cpp
    src/ProxyHelper.cpp
)

target_include_directories(ProxyHelperTests PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
)

target_compile_definitions(ProxyHelperTests PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
)

target_link_libraries(ProxyHelperTests PRIVATE
    winhttp
    ws2_32
)

if(MSVC)
    target_compile_options(ProxyHelperTests PRIVATE /utf-8)
endif()

add_test(NAME ProxyHelperTests COMMAND ProxyHelperTests)

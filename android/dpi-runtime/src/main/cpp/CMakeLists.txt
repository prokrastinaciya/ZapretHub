cmake_minimum_required(VERSION 3.22.1)

project(dpi_runtime)

# The arch-specific context-switch assembly is written as preprocessed `.s`
# (it uses #include/#define), so ASM must be enabled explicitly.
enable_language(ASM)

# The native engine sources are pinned (immutable) under upstream/byebyedpi/.
# This module compiles them in place: it never copies or modifies the snapshot.
# Local JNI adapters expose listener and tunnel readiness.
set(UPSTREAM_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../upstream/byebyedpi/app/src/main/cpp")

file(GLOB BYE_DPI_SRC "${UPSTREAM_CPP}/byedpi/*.c")
list(REMOVE_ITEM BYE_DPI_SRC "${UPSTREAM_CPP}/byedpi/win_service.c")

add_library(byedpi SHARED
    ${BYE_DPI_SRC}
    "${UPSTREAM_CPP}/byedpi-jni.c"
    "${UPSTREAM_CPP}/hev-jni.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/dpi-jni.c")

target_include_directories(byedpi PRIVATE
    "${UPSTREAM_CPP}"
    "${UPSTREAM_CPP}/byedpi")

target_compile_options(byedpi PRIVATE
    -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

target_link_libraries(byedpi PRIVATE android log dl)
target_link_options(byedpi PRIVATE "-Wl,--wrap=daemon" "-Wl,--wrap=listen")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# libhev-socks5-tunnel.so — the HEV TUN->SOCKS5 tunnel for VPN mode. The pinned
# upstream builds this with ndk-build (app/src/main/jni/Android.mk). This
# target builds the same tunnel core and third-party libraries. Each
# platform-specific translation unit is guarded by its platform macro (e.g.
# lwip's win32 port is `#if _WIN32`), so globbing the whole tree is faithful to
# the upstream `rwildcard` and compiles cleanly on Android.
# ---------------------------------------------------------------------------
set(HEV_TUNNEL "${CMAKE_CURRENT_SOURCE_DIR}/../../../../upstream/byebyedpi/app/src/main/jni/hev-socks5-tunnel")

# libyaml (static)
file(GLOB YAML_SRC "${HEV_TUNNEL}/third-part/yaml/src/*.c")
add_library(yaml STATIC ${YAML_SRC})
target_include_directories(yaml PUBLIC "${HEV_TUNNEL}/third-part/yaml/src")
target_compile_options(yaml PRIVATE -O3)
target_compile_definitions(yaml PRIVATE
    YAML_VERSION_MAJOR=0
    YAML_VERSION_MINOR=2
    YAML_VERSION_PATCH=5
    "YAML_VERSION_STRING=\"0.2.5\"")

# liblwip (static)
file(GLOB_RECURSE LWIP_SRC "${HEV_TUNNEL}/third-part/lwip/src/*.c" "${HEV_TUNNEL}/third-part/lwip/src/*.S")
add_library(lwip STATIC ${LWIP_SRC})
target_include_directories(lwip PUBLIC
    "${HEV_TUNNEL}/third-part/lwip/src/include"
    "${HEV_TUNNEL}/third-part/lwip/src/ports/include")
target_compile_options(lwip PRIVATE -O3)
target_compile_definitions(lwip PRIVATE FD_SET_DEFINED SOCKLEN_T_DEFINED)

# libhev-task-system (static). The context-switch primitive is dispatched by the
# preprocessed `hev-task-execute.S` (uppercase), which #includes the
# arch-specific lowercase `.s` for the target ABI. On a case-insensitive
# filesystem a `*.S` glob would also match every lowercase `.s`, so glob only
# `*.c` and list the single dispatcher explicitly.
file(GLOB_RECURSE HEV_TASK_SRC "${HEV_TUNNEL}/third-part/hev-task-system/src/*.c")
add_library(hev-task-system STATIC ${HEV_TASK_SRC}
    "${HEV_TUNNEL}/third-part/hev-task-system/src/kern/task/hev-task-execute.S")
target_include_directories(hev-task-system PUBLIC
    "${HEV_TUNNEL}/third-part/hev-task-system/src"
    "${HEV_TUNNEL}/third-part/hev-task-system/include")
target_compile_options(hev-task-system PRIVATE -O3 -fvisibility=hidden)
target_compile_definitions(hev-task-system PRIVATE
    ENABLE_STACK_OVERFLOW_DETECTION
    ENABLE_MEMALLOC_SLICE
    ENABLE_IO_SPLICE_SYSCALL
    CONFIG_STACK_BACKEND=STACK_MMAP
    CONFIG_STACK_OVERFLOW_DETECTION=1
    CONFIG_MEMALLOC_SLICE_ALIGN=64
    CONFIG_MEMALLOC_SLICE_MAX_SIZE=4096
    CONFIG_MEMALLOC_SLICE_MAX_COUNT=1000
    CONFIG_SCHED_CLOCK=CLOCK_NONE)

# libhev-socks5-tunnel.so (shared)
file(GLOB_RECURSE TUNNEL_SRC "${HEV_TUNNEL}/src/*.c" "${HEV_TUNNEL}/src/*.S")
# ByeByeDPI already supplies its own TProxyService JNI via UPSTREAM_CPP/hev-jni.c.
# The standalone HEV Java bridge registers a different ABI on JNI_OnLoad.
list(REMOVE_ITEM TUNNEL_SRC "${HEV_TUNNEL}/src/hev-jni.c")
add_library(hev-socks5-tunnel SHARED ${TUNNEL_SRC} "${CMAKE_CURRENT_SOURCE_DIR}/hev-ready-jni.c")
target_include_directories(hev-socks5-tunnel PRIVATE
    "${HEV_TUNNEL}/src"
    "${HEV_TUNNEL}/src/misc"
    "${HEV_TUNNEL}/src/core/include"
    "${HEV_TUNNEL}/third-part/yaml/include"
    "${HEV_TUNNEL}/third-part/lwip/src/include"
    "${HEV_TUNNEL}/third-part/lwip/src/ports/include"
    "${HEV_TUNNEL}/third-part/hev-task-system/include")
target_compile_options(hev-socks5-tunnel PRIVATE -O3)
target_compile_definitions(hev-socks5-tunnel PRIVATE
    ANDROID
    ENABLE_LIBRARY
    FD_SET_DEFINED
    SOCKLEN_T_DEFINED)
target_link_libraries(hev-socks5-tunnel PRIVATE yaml lwip hev-task-system android log)
# --wrap=hev_socks5_tunnel_run exposes the genuine native-ready signal (see
# hev-ready-jni.c): the pinned tunnel calls that symbol exactly once, only after
# a successful init, so reaching it is positive native readiness.
target_link_options(hev-socks5-tunnel PRIVATE "-Wl,-z,common-page-size=16384" "-Wl,--wrap=hev_socks5_tunnel_run")

cmake_minimum_required(VERSION 3.16)
project(AIOS_Core VERSION 1.8.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

set(LLAMA_DIR "${CMAKE_SOURCE_DIR}/llama.cpp")

find_package(Threads REQUIRED)
find_package(nlohmann_json 3.2.0 REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(CPP-HTTPLIB REQUIRED cpp-httplib)
find_package(OpenSSL REQUIRED)
find_package(OpenMP)

add_executable(aios_core
    src/main.cpp
    src/task_scheduler.cpp
    src/thread_pool.cpp
    src/syscall_server.cpp
    src/memory_manager.cpp
    src/llm_adapter.cpp
    src/env_loader.cpp
    src/sandbox_driver.cpp
    src/cache_manager.cpp
    src/instruction_decoder.cpp
    src/wasm_node.cpp
    src/compiler_bridge.cpp
    src/kernel_logger.cpp
    src/semantic_node.cpp
)

target_include_directories(aios_core PRIVATE
    ${CMAKE_SOURCE_DIR}/include
    /usr/local/include
)

target_link_libraries(aios_core PRIVATE
    Threads::Threads
    nlohmann_json::nlohmann_json
    cpp-httplib
    OpenSSL::SSL
    OpenSSL::Crypto
    $<$<BOOL:${OpenMP_CXX_FOUND}>:OpenMP::OpenMP_CXX>
    /usr/local/lib/libwasmedge.so
    dl
    rt
    m
)

target_compile_definitions(aios_core PRIVATE
    CPPHTTPLIB_OPENSSL_SUPPORT
)

target_compile_options(aios_core PRIVATE
    -Wall -Wextra -Wpedantic
)

if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(aios_core PRIVATE -O3 -march=native)
endif()

add_executable(aios_decoder
    src/DecoderDaemon.cpp
)

target_include_directories(aios_decoder PRIVATE
    ${CMAKE_SOURCE_DIR}/include
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(aios_decoder PRIVATE
    Threads::Threads
    ${LLAMA_DIR}/build/src/libllama.a
    ${LLAMA_DIR}/build/ggml/src/libggml.a
    ${LLAMA_DIR}/build/ggml/src/libggml-cpu.a
    ${LLAMA_DIR}/build/ggml/src/libggml-base.a
    $<$<BOOL:${OpenMP_CXX_FOUND}>:OpenMP::OpenMP_CXX>
    dl
    rt
    m
)

target_compile_options(aios_decoder PRIVATE
    -Wall -Wextra -Wpedantic
)

if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(aios_decoder PRIVATE -O3 -march=native)
endif()

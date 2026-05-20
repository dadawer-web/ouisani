cmake_minimum_required(VERSION 3.16)
project(AIOS_Core VERSION 1.5.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

find_package(Threads REQUIRED)
find_package(nlohmann_json 3.2.0 REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(CPP-HTTPLIB REQUIRED cpp-httplib)
find_package(OpenSSL REQUIRED)

add_executable(aios_core
    src/main.cpp
    src/task_scheduler.cpp
    src/thread_pool.cpp
    src/syscall_server.cpp
    src/memory_manager.cpp
    src/llm_adapter.cpp
    src/env_loader.cpp
    src/sandbox_driver.cpp
)

target_include_directories(aios_core PRIVATE
    ${CMAKE_SOURCE_DIR}/include
)

target_link_libraries(aios_core PRIVATE
    Threads::Threads
    nlohmann_json::nlohmann_json
    cpp-httplib
    OpenSSL::SSL
    OpenSSL::Crypto
)

target_compile_definitions(aios_core PRIVATE
    CPPHTTPLIB_OPENSSL_SUPPORT
)

target_compile_options(aios_core PRIVATE
    -Wall -Wextra -Wpedantic
)

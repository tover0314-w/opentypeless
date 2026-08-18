# SPDX-License-Identifier: MIT
# Copyright (c) 2025 OpenTypeless Contributors

cmake_minimum_required(VERSION 3.22)
project(opentypeless_rime_adapter LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT DEFINED RIME_SHARED_LIBRARY)
  message(FATAL_ERROR "RIME_SHARED_LIBRARY is required")
endif()
if(NOT DEFINED RIME_SOURCE_DIR)
  message(FATAL_ERROR "RIME_SOURCE_DIR is required")
endif()

add_library(rime SHARED IMPORTED)
set_target_properties(rime PROPERTIES IMPORTED_LOCATION "${RIME_SHARED_LIBRARY}")

add_library(opentypeless_rime SHARED rime_jni.cc)
target_include_directories(opentypeless_rime PRIVATE "${RIME_SOURCE_DIR}/src")
target_compile_options(opentypeless_rime PRIVATE
  -fvisibility=hidden
  -fvisibility-inlines-hidden
  -Wall
  -Wextra
  -Werror)
target_link_libraries(opentypeless_rime PRIVATE rime)


# SPDX-License-Identifier: MIT
#
# Native tests and benchmarks. These link the core library directly, so they run
# with no JVM present. The equivalence harness compares against golden data that
# the Java reference implementation produced (see tools/GenerateGolden.java).

add_executable(wrobj_tests
        wrobj_tests.cpp
        golden_format.cpp)

target_link_libraries(wrobj_tests PRIVATE wrobj::core)
target_include_directories(wrobj_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if(MSVC)
    target_compile_options(wrobj_tests PRIVATE /W4 /permissive- /utf-8 /fp:precise)
    target_compile_definitions(wrobj_tests PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX)
else()
    target_compile_options(wrobj_tests PRIVATE -Wall -Wextra)
endif()

add_test(NAME wrobj_tests COMMAND wrobj_tests)

# Tests for the native draw list. These run with a recording stub in place of a GL
# driver, so they assert on the exact sequence and *count* of GL calls the renderer
# would have made - which is the quantity the draw-path rewrite changes.
add_executable(wrgl_tests wrgl_tests.cpp)
target_link_libraries(wrgl_tests PRIVATE wrobj::core)
if(MSVC)
    target_compile_options(wrgl_tests PRIVATE /W4 /permissive- /utf-8 /fp:precise)
    target_compile_definitions(wrgl_tests PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX)
else()
    target_compile_options(wrgl_tests PRIVATE -Wall -Wextra)
endif()
add_test(NAME wrgl_tests COMMAND wrgl_tests)

# Colour packing probe. This exists because the ARGB pack uses a signed left
# shift that is undefined behaviour before C++20 for any red channel above 127;
# the probe prints every intermediate so a packing mismatch can be attributed to
# the arithmetic rather than to the surrounding pipeline.
add_executable(wrobj_color_probe color_probe.cpp)
if(MSVC)
    target_compile_options(wrobj_color_probe PRIVATE /W4 /permissive- /utf-8 /fp:precise)
else()
    target_compile_options(wrobj_color_probe PRIVATE -Wall -Wextra)
endif()

# The benchmark is opt-in: it exists to produce numbers for the report, not to
# gate a build.
add_executable(wrobj_bench
        wrobj_bench.cpp
        golden_format.cpp)

target_link_libraries(wrobj_bench PRIVATE wrobj::core)
target_include_directories(wrobj_bench PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

if(MSVC)
    target_compile_options(wrobj_bench PRIVATE /W4 /permissive- /utf-8 /fp:precise /O2)
    target_compile_definitions(wrobj_bench PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX)
else()
    target_compile_options(wrobj_bench PRIVATE -Wall -Wextra -O2)
endif()

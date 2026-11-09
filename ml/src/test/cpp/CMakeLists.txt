# Host build of the native unit tests (no JNI, no Android):
#   cmake -S ml/src/test/cpp -B build/native-test && cmake --build build/native-test -j && ctest --test-dir build/native-test
cmake_minimum_required(VERSION 3.22.1)
project(yomu_ml_native_test CXX C)

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp llama.cpp)

enable_testing()

add_executable(prompt_budget_test prompt_budget_test.cpp)
add_test(NAME prompt_budget_test COMMAND prompt_budget_test)

add_executable(chat_prompt_test chat_prompt_test.cpp ../../main/cpp/chat_prompt.cpp)
target_link_libraries(chat_prompt_test PRIVATE llama-common)
target_compile_definitions(chat_prompt_test PRIVATE TEMPLATE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/templates")
# assert() is the check; keep it in every build type.
target_compile_options(chat_prompt_test PRIVATE -UNDEBUG)
target_compile_options(prompt_budget_test PRIVATE -UNDEBUG)
add_test(NAME chat_prompt_test COMMAND chat_prompt_test)

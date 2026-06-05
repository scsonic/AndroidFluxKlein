cmake_minimum_required(VERSION 3.22.1)

project("fluxklein")

set(CMAKE_CXX_STANDARD 17)

# ---- Prebuilt MNN shared libraries (from jniLibs) ----
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so)

add_library(MNN_CL SHARED IMPORTED)
set_target_properties(MNN_CL PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN_CL.so)

add_library(llm SHARED IMPORTED)
set_target_properties(llm PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllm.so)

add_library(diffusion SHARED IMPORTED)
set_target_properties(diffusion PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libdiffusion.so)

# ---- MNN header paths (relative to workspace root) ----
# From .../AndroidFluxKlein/libFluxKlein/src/main/cpp → 5 levels up = mnn_flux_klein/
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../MNN/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../MNN/transformers/diffusion/engine/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../../../../MNN/transformers/llm/engine/include)

# ---- JNI bridge library ----
add_library(fluxklein SHARED fluxklein-jni.cpp)

find_library(log-lib log)

target_link_libraries(fluxklein
    MNN
    MNN_CL
    llm
    diffusion
    ${log-lib})

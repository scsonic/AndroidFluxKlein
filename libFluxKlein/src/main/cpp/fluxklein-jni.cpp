#include "diffusion/diffusion.hpp"
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <memory>
#include <string>

#define TAG     "FluxKlein"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace MNN::DIFFUSION;

#include <stdexcept>

// com.scsonic.fluxklein.FluxKlein#nativeGenerate(
//   String modelPath, String prompt,
//   int seed, int steps, int imageWidth, int imageHeight,
//   String outPath, String inputImagePath,
//   int gpuBackend, boolean textEncoderOnCPU, boolean vaeOnCPU,
//   float cfgScale,
//   ProgressListener listener            <- may be null
// ) : boolean
extern "C" JNIEXPORT jboolean JNICALL
Java_com_scsonic_fluxklein_FluxKlein_nativeGenerate(
        JNIEnv  *env,
        jclass  /* clazz */,
        jstring  jModelPath,
        jstring  jPrompt,
        jint     jSeed,
        jint     jSteps,
        jint     jWidth,
        jint     jHeight,
        jstring  jOutPath,
        jstring  jImagePath,
        jint     jGpuBackend,
        jboolean jTextEncoderOnCPU,
        jboolean jVaeOnCPU,
        jfloat   jCfgScale,
        jobject  jListener)
{
    // ---- String conversions ----
    const char *cModelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char *cPrompt    = env->GetStringUTFChars(jPrompt,    nullptr);
    const char *cOutPath   = env->GetStringUTFChars(jOutPath,   nullptr);
    const char *cImagePath = env->GetStringUTFChars(jImagePath, nullptr);

    std::string sModelPath(cModelPath);
    std::string sPrompt(cPrompt);
    std::string sOutPath(cOutPath);
    std::string sImagePath(cImagePath);

    env->ReleaseStringUTFChars(jModelPath, cModelPath);
    env->ReleaseStringUTFChars(jPrompt,    cPrompt);
    env->ReleaseStringUTFChars(jOutPath,   cOutPath);
    env->ReleaseStringUTFChars(jImagePath, cImagePath);

    int seed   = static_cast<int>(jSeed);
    int steps  = static_cast<int>(jSteps);
    int width  = static_cast<int>(jWidth);
    int height = static_cast<int>(jHeight);
    if (seed == -1) seed = 42; // fallback; caller should randomise before passing

    // gpuBackend: 0=OpenCL, 1=Vulkan, 2=CPU, 3=QNN/NPU (MNN_FORWARD_NN)
    MNNForwardType gpuFwdType;
    if      (jGpuBackend == 1) gpuFwdType = MNN_FORWARD_VULKAN;
    else if (jGpuBackend == 2) gpuFwdType = MNN_FORWARD_CPU;
    else if (jGpuBackend == 3) gpuFwdType = MNN_FORWARD_NN;
    else                       gpuFwdType = MNN_FORWARD_OPENCL;
    bool teOnCPU  = (jTextEncoderOnCPU  == JNI_TRUE);
    bool vaeOnCPU = (jVaeOnCPU == JNI_TRUE);
    float cfgScale = static_cast<float>(jCfgScale);

    // ---- Resolve ProgressListener.onProgress(I)V ----
    jmethodID onProgressMid = nullptr;
    if (jListener != nullptr) {
        jclass listenerCls = env->GetObjectClass(jListener);
        onProgressMid = env->GetMethodID(listenerCls, "onProgress", "(I)V");
        env->DeleteLocalRef(listenerCls);
    }

    auto progressCallback = [&](int progress) {
        if (jListener != nullptr && onProgressMid != nullptr) {
            env->CallVoidMethod(jListener, onProgressMid, static_cast<jint>(progress));
        }
    };

    // ---- Create and run Diffusion ----
    const char* gpuName = (jGpuBackend == 1) ? "Vulkan"
                        : (jGpuBackend == 2) ? "CPU"
                        : (jGpuBackend == 3) ? "NPU(QNN)"
                        : "OpenCL";
    LOGI("Creating diffusion: size=%dx%d steps=%d seed=%d gpu=%s te=%s vae=%s cfg=%.1f",
         width, height, steps, seed,
         gpuName,
         teOnCPU  ? "CPU" : "GPU",
         vaeOnCPU ? "CPU" : "GPU",
         cfgScale);

    try {
        std::unique_ptr<Diffusion> diffusion(Diffusion::createDiffusion(
                sModelPath,
                FLUX2_KLEIN_DIFFUSION,
                gpuFwdType,
                /*memoryMode=*/0,
                width, height,
                teOnCPU,
                vaeOnCPU,
                GPU_MEMORY_AUTO,
                PRECISION_AUTO,
                CFG_MODE_AUTO,
                /*numThreads=*/8));

        if (!diffusion) {
            LOGE("Failed to create Diffusion instance");
            return JNI_FALSE;
        }

        LOGI("Loading model from %s ...", sModelPath.c_str());
        if (!diffusion->load()) {
            LOGE("Failed to load model");
            return JNI_FALSE;
        }

        LOGI("Running inference...");
        bool success = diffusion->run(sPrompt, sOutPath, steps, seed,
                                      cfgScale,
                                      progressCallback, sImagePath);

        LOGI("Inference %s", success ? "succeeded" : "FAILED");
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("Fatal native exception caught in JNI: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown native exception caught in JNI");
        return JNI_FALSE;
    }
}

// com.scsonic.fluxklein.FluxKlein#checkNpuAvailable() : boolean
// Loads libQnnHtp.so and verifies the QNN HTP interface is present and usable.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_scsonic_fluxklein_FluxKlein_checkNpuAvailable(
        JNIEnv  * /* env */,
        jclass  /* clazz */)
{
    void* handle = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        LOGI("NPU check: dlopen(libQnnHtp.so) failed: %s", dlerror());
        return JNI_FALSE;
    }
    void* sym = dlsym(handle, "QnnInterface_getProviders");
    if (!sym) {
        LOGI("NPU check: QnnInterface_getProviders not found: %s", dlerror());
        dlclose(handle);
        return JNI_FALSE;
    }
    dlclose(handle);
    LOGI("NPU check: libQnnHtp.so OK — QNN HTP backend available");
    return JNI_TRUE;
}

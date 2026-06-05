#include "diffusion/diffusion.hpp"
#include <android/log.h>
#include <jni.h>
#include <memory>
#include <string>

#define TAG     "FluxKlein"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace MNN::DIFFUSION;

// com.scsonic.fluxklein.FluxKlein#nativeGenerate(
//   String modelPath, String prompt,
//   int seed, int steps, int imageWidth, int imageHeight,
//   String outPath, String inputImagePath,
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
    LOGI("Creating diffusion instance: size=%dx%d steps=%d seed=%d", width, height, steps, seed);

    std::unique_ptr<Diffusion> diffusion(Diffusion::createDiffusion(
            sModelPath,
            FLUX2_KLEIN_DIFFUSION,
            MNN_FORWARD_OPENCL,
            /*memoryMode=*/0,
            width, height,
            /*textEncoderOnCPU=*/true,
            /*vaeOnCPU=*/true,
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
                                  /*cfgScale=*/1.0f,
                                  progressCallback, sImagePath);

    LOGI("Inference %s", success ? "succeeded" : "FAILED");
    return success ? JNI_TRUE : JNI_FALSE;
}

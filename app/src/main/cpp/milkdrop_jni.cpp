// JNI bridge between the visualizer's MilkDrop look (MilkDrop.java) and libprojectM's C API.
// Every call except available() runs on MilkDrop's own GL thread with its EGL context current.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <string>

#ifdef HAVE_PROJECTM
#include <EGL/egl.h>
#include <dlfcn.h>
#include <projectM-4/projectM.h>

namespace {
struct Instance {
    projectm_handle pm = nullptr;
    std::atomic<int> request{0};      // 1 = projectM asked for a new preset on a hard cut (the drop), 2 = soft
    std::string failed;               // the last preset that would not load, for the graphics log
};

void* glesLib = nullptr;

void* loadProc(const char* name, void*) {
    if (!glesLib) glesLib = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
    void* p = glesLib ? dlsym(glesLib, name) : nullptr;
    if (!p) p = reinterpret_cast<void*>(eglGetProcAddress(name));
    return p;
}

void onSwitch(bool hardCut, void* user) {
    static_cast<Instance*>(user)->request.store(hardCut ? 1 : 2);
}

void onFailed(const char* file, const char* message, void* user) {
    auto* in = static_cast<Instance*>(user);
    in->failed = std::string(file ? file : "?") + ": " + (message ? message : "failed");
    in->request.store(2);             // move on to another preset
}

Instance* of(jlong h) { return reinterpret_cast<Instance*>(h); }
}  // namespace
#endif

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_strickland_jeepdash_MilkDrop_available(JNIEnv*, jclass) {
#ifdef HAVE_PROJECTM
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL Java_com_strickland_jeepdash_MilkDrop_create(JNIEnv* env, jclass, jint w, jint h,
                                                                   jstring textures) {
#ifdef HAVE_PROJECTM
    auto* in = new Instance();
    in->pm = projectm_create_with_opengl_load_proc(loadProc, nullptr);
    if (!in->pm) {
        __android_log_print(ANDROID_LOG_ERROR, "MilkDrop", "projectm_create failed");
        delete in;
        return 0;
    }
    projectm_set_window_size(in->pm, (size_t) w, (size_t) h);
    projectm_set_preset_switch_requested_event_callback(in->pm, onSwitch, in);
    projectm_set_preset_switch_failed_event_callback(in->pm, onFailed, in);
    projectm_set_preset_duration(in->pm, 100000.0);          // no timed switching: only the drop, or the buttons
    projectm_set_soft_cut_duration(in->pm, 2.0);
    projectm_set_aspect_correction(in->pm, true);
    if (textures) {
        const char* path = env->GetStringUTFChars(textures, nullptr);
        const char* paths[] = {path};
        projectm_set_texture_search_paths(in->pm, paths, 1);
        env->ReleaseStringUTFChars(textures, path);
    }
    return reinterpret_cast<jlong>(in);
#else
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_com_strickland_jeepdash_MilkDrop_resize(JNIEnv*, jclass, jlong h, jint w, jint ht) {
#ifdef HAVE_PROJECTM
    if (h) projectm_set_window_size(of(h)->pm, (size_t) w, (size_t) ht);
#endif
}

JNIEXPORT void JNICALL Java_com_strickland_jeepdash_MilkDrop_settings(JNIEnv*, jclass, jlong h, jboolean drop,
                                                                    jfloat hold, jfloat sensitivity, jboolean locked,
                                                                    jint meshW, jint meshH, jint fps) {
#ifdef HAVE_PROJECTM
    if (!h) return;
    projectm_handle pm = of(h)->pm;
    projectm_set_hard_cut_enabled(pm, drop);
    projectm_set_hard_cut_duration(pm, hold);
    projectm_set_hard_cut_sensitivity(pm, sensitivity);
    projectm_set_beat_sensitivity(pm, sensitivity);
    projectm_set_preset_locked(pm, locked);
    projectm_set_mesh_size(pm, (size_t) meshW, (size_t) meshH);
    projectm_set_fps(pm, fps);
#endif
}

JNIEXPORT void JNICALL Java_com_strickland_jeepdash_MilkDrop_pcm(JNIEnv* env, jclass, jlong h, jshortArray samples,
                                                               jint count) {
#ifdef HAVE_PROJECTM
    if (!h || count <= 0) return;
    jshort* s = env->GetShortArrayElements(samples, nullptr);
    projectm_pcm_add_int16(of(h)->pm, reinterpret_cast<const int16_t*>(s), (unsigned int) count, PROJECTM_MONO);
    env->ReleaseShortArrayElements(samples, s, JNI_ABORT);
#endif
}

JNIEXPORT jint JNICALL Java_com_strickland_jeepdash_MilkDrop_render(JNIEnv*, jclass, jlong h) {
#ifdef HAVE_PROJECTM
    if (!h) return 0;
    projectm_opengl_render_frame(of(h)->pm);
    return of(h)->request.exchange(0);
#else
    return 0;
#endif
}

JNIEXPORT void JNICALL Java_com_strickland_jeepdash_MilkDrop_load(JNIEnv* env, jclass, jlong h, jstring file,
                                                                jboolean smooth) {
#ifdef HAVE_PROJECTM
    if (!h) return;
    const char* path = env->GetStringUTFChars(file, nullptr);
    projectm_load_preset_file(of(h)->pm, path, smooth);
    env->ReleaseStringUTFChars(file, path);
#endif
}

JNIEXPORT jstring JNICALL Java_com_strickland_jeepdash_MilkDrop_failed(JNIEnv* env, jclass, jlong h) {
#ifdef HAVE_PROJECTM
    if (!h || of(h)->failed.empty()) return nullptr;
    jstring out = env->NewStringUTF(of(h)->failed.c_str());
    of(h)->failed.clear();
    return out;
#else
    return nullptr;
#endif
}

JNIEXPORT void JNICALL Java_com_strickland_jeepdash_MilkDrop_destroy(JNIEnv*, jclass, jlong h) {
#ifdef HAVE_PROJECTM
    if (!h) return;
    projectm_destroy(of(h)->pm);
    delete of(h);
#endif
}

}  // extern "C"

#include <jni.h>

#include "generated/java_mangle.h"

static jstring fetch(JNIEnv *env, jclass) {
    jclass holder = env->FindClass(COM_EXAMPLE_HELLOKTS_NATIVEGREETING);
    jmethodID method = env->GetStaticMethodID(
            holder, COM_EXAMPLE_HELLOKTS_NATIVEGREETING_FROMNATIVE, "()Ljava/lang/String;");
    return static_cast<jstring>(env->CallStaticObjectMethod(holder, method));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_hellokts_MainActivity_kotlinNative(JNIEnv *env, jobject) {
    return env->NewStringUTF("kotlin");
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    const JNINativeMethod methods[] = {
            {COM_EXAMPLE_HELLOKTS_NATIVEGREETING_FETCH, "()Ljava/lang/String;",
             reinterpret_cast<void *>(fetch)},
    };
    jclass holder = env->FindClass(COM_EXAMPLE_HELLOKTS_NATIVEGREETING);
    if (holder == nullptr || env->RegisterNatives(holder, methods, 1) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}

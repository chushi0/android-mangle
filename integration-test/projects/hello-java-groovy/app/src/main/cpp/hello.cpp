#include <jni.h>

#include "java_mangle.h"

static jstring nativeGreeting(JNIEnv *env, jclass, jobject /* self */) {
    jclass bridge = env->FindClass(COM_EXAMPLE_HELLO_GREETINGBRIDGE);
    if (bridge == nullptr) return nullptr;
    jmethodID read = env->GetStaticMethodID(
            bridge, COM_EXAMPLE_HELLO_GREETINGBRIDGE_READGREETING, "()Ljava/lang/String;");
    if (read == nullptr) return nullptr;
    return static_cast<jstring>(env->CallStaticObjectMethod(bridge, read));
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass holder = env->FindClass(COM_EXAMPLE_HELLO_MAINACTIVITY);
    if (holder == nullptr) return JNI_ERR;
    const JNINativeMethod methods[] = {
            {COM_EXAMPLE_HELLO_MAINACTIVITY_NATIVEGREETING,
             "(Ljava/lang/Object;)Ljava/lang/String;",
             reinterpret_cast<void *>(nativeGreeting)},
    };
    if (env->RegisterNatives(holder, methods, 1) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}

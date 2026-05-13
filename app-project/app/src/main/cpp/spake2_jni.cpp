// JNI shim that links statically against BoringSSL (provided as a Prefab AAR via
// io.github.vvb2060.ndk:boringssl). This is the same SPAKE2 implementation adbd uses,
// so keys derive byte-for-byte identically — no protocol drift, no AES-GCM MAC failures.
//
// Earlier paths that did NOT work:
//   • spake2-java (pure-Java): upstream bug, Alice/Bob keys diverge (issue #1).
//   • dlopen system libcrypto.so: blocked by Android linker namespace isolation; the
//     symbol that would let us reach Conscrypt's libcrypto (android_get_exported_namespace)
//     is in libdl.so's LIBC_PLATFORM version, which apps cannot link against.

#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <android/log.h>
#include <openssl/curve25519.h>

#define TAG "Spake2Jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void throwISE(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, msg);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_halo_ring_adb_NativeSpake2_nativeNew(JNIEnv* env, jclass,
                                              jint role,
                                              jbyteArray myName,
                                              jbyteArray theirName) {
    jbyte* myN  = env->GetByteArrayElements(myName,    nullptr);
    jbyte* thN  = env->GetByteArrayElements(theirName, nullptr);
    jsize myL   = env->GetArrayLength(myName);
    jsize thL   = env->GetArrayLength(theirName);

    SPAKE2_CTX* ctx = SPAKE2_CTX_new((spake2_role_t)role,
                                     (const uint8_t*)myN, (size_t)myL,
                                     (const uint8_t*)thN, (size_t)thL);

    env->ReleaseByteArrayElements(myName,    myN, JNI_ABORT);
    env->ReleaseByteArrayElements(theirName, thN, JNI_ABORT);

    if (!ctx) { throwISE(env, "SPAKE2_CTX_new failed"); return 0; }
    return (jlong)(uintptr_t)ctx;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_halo_ring_adb_NativeSpake2_nativeGenerate(JNIEnv* env, jclass,
                                                   jlong ctxPtr,
                                                   jbyteArray password) {
    if (!ctxPtr) { throwISE(env, "invalid ctx"); return nullptr; }
    auto* ctx = (SPAKE2_CTX*)(uintptr_t)ctxPtr;

    jbyte* pwd  = env->GetByteArrayElements(password, nullptr);
    jsize  pwdL = env->GetArrayLength(password);

    uint8_t out[SPAKE2_MAX_MSG_SIZE];
    size_t  out_len = 0;
    int ok = SPAKE2_generate_msg(ctx, out, &out_len, sizeof(out),
                                 (const uint8_t*)pwd, (size_t)pwdL);

    env->ReleaseByteArrayElements(password, pwd, JNI_ABORT);

    if (!ok || out_len != 32) { throwISE(env, "SPAKE2_generate_msg failed"); return nullptr; }
    jbyteArray ret = env->NewByteArray(32);
    env->SetByteArrayRegion(ret, 0, 32, (const jbyte*)out);
    return ret;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_halo_ring_adb_NativeSpake2_nativeProcess(JNIEnv* env, jclass,
                                                  jlong ctxPtr,
                                                  jbyteArray theirMsg) {
    if (!ctxPtr) { throwISE(env, "invalid ctx"); return nullptr; }
    auto* ctx = (SPAKE2_CTX*)(uintptr_t)ctxPtr;

    jbyte* their  = env->GetByteArrayElements(theirMsg, nullptr);
    jsize  theirL = env->GetArrayLength(theirMsg);

    uint8_t key[SPAKE2_MAX_KEY_SIZE];
    size_t  key_len = 0;
    int ok = SPAKE2_process_msg(ctx, key, &key_len, sizeof(key),
                                (const uint8_t*)their, (size_t)theirL);

    env->ReleaseByteArrayElements(theirMsg, their, JNI_ABORT);

    if (!ok || key_len != 64) { throwISE(env, "SPAKE2_process_msg failed"); return nullptr; }
    jbyteArray ret = env->NewByteArray(64);
    env->SetByteArrayRegion(ret, 0, 64, (const jbyte*)key);
    return ret;
}

extern "C" JNIEXPORT void JNICALL
Java_com_halo_ring_adb_NativeSpake2_nativeFree(JNIEnv*, jclass, jlong ctxPtr) {
    if (ctxPtr) SPAKE2_CTX_free((SPAKE2_CTX*)(uintptr_t)ctxPtr);
}

//go:build android && cgo

#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jlong JNICALL Java_com_codexrm_mobile_NativeP2P_open(JNIEnv *env, jobject self, jbyteArray data) {
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    if (!b) return 0;
    jlong h = CRMOpen(b, (*env)->GetArrayLength(env, data));
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    return h;
}

JNIEXPORT jbyteArray JNICALL Java_com_codexrm_mobile_NativeP2P_poll(JNIEnv *env, jobject self, jlong h) {
    int n = 0;
    void *b = CRMPoll(h, &n);
    if (!b) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, n, b);
    free(b);
    return out;
}

JNIEXPORT void JNICALL Java_com_codexrm_mobile_NativeP2P_signal(JNIEnv *env, jobject self, jlong h, jbyteArray data) {
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    if (!b) return;
    CRMSignal(h, b, (*env)->GetArrayLength(env, data));
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL Java_com_codexrm_mobile_NativeP2P_send(JNIEnv *env, jobject self, jlong h, jbyteArray data) {
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    if (!b) return JNI_FALSE;
    jboolean sent = CRMSend(h, b, (*env)->GetArrayLength(env, data)) ? JNI_TRUE : JNI_FALSE;
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    return sent;
}

JNIEXPORT void JNICALL Java_com_codexrm_mobile_NativeP2P_close(JNIEnv *env, jobject self, jlong h) {
    CRMClose(h);
}

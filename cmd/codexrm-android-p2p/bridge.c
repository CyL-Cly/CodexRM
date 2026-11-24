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

JNIEXPORT jboolean JNICALL Java_com_codexrm_mobile_NativeP2P_upload(JNIEnv *env, jobject self, jlong h, jbyteArray id, jbyteArray path, jlong size) {
    jbyte *id_bytes = (*env)->GetByteArrayElements(env, id, NULL);
    if (!id_bytes) return JNI_FALSE;
    jbyte *path_bytes = (*env)->GetByteArrayElements(env, path, NULL);
    jboolean ok = JNI_FALSE;
    if (path_bytes) {
        ok = CRMUpload(h, id_bytes, (*env)->GetArrayLength(env, id), path_bytes, (*env)->GetArrayLength(env, path), size) ? JNI_TRUE : JNI_FALSE;
        (*env)->ReleaseByteArrayElements(env, path, path_bytes, JNI_ABORT);
    }
    (*env)->ReleaseByteArrayElements(env, id, id_bytes, JNI_ABORT);
    return ok;
}

JNIEXPORT jint JNICALL Java_com_codexrm_mobile_NativeP2P_uploadProgress(JNIEnv *env, jobject self, jlong h) {
    return CRMUploadProgress(h);
}

JNIEXPORT void JNICALL Java_com_codexrm_mobile_NativeP2P_close(JNIEnv *env, jobject self, jlong h) {
    CRMClose(h);
}

JNIEXPORT jbyteArray JNICALL Java_com_codexrm_mobile_NativeP2P_diagnoseNat(JNIEnv *env, jobject self, jbyteArray data) {
    jsize size = (*env)->GetArrayLength(env, data);
    jbyte b[1024];
    if (size <= 0 || size > (jsize)sizeof(b)) return NULL;
    (*env)->GetByteArrayRegion(env, data, 0, size, b);
    if ((*env)->ExceptionCheck(env)) return NULL;
    int n = 0;
    void *result = CRMDiagnoseNAT(b, size, &n);
    if (!result) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, n, result);
    free(result);
    return out;
}

JNIEXPORT jboolean JNICALL Java_com_codexrm_mobile_NativeP2P_startDesktop(JNIEnv *env, jobject self, jlong h) {
    return CRMDesktopStart(h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_codexrm_mobile_NativeP2P_stopDesktop(JNIEnv *env, jobject self, jlong h) {
    CRMDesktopStop(h);
}

JNIEXPORT jboolean JNICALL Java_com_codexrm_mobile_NativeP2P_desktopActive(JNIEnv *env, jobject self, jlong h) {
    return CRMDesktopActive(h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_codexrm_mobile_NativeP2P_desktopBytes(JNIEnv *env, jobject self, jlong h) {
    return (jlong)CRMDesktopBytes(h);
}

JNIEXPORT jbyteArray JNICALL Java_com_codexrm_mobile_NativeP2P_pollDesktop(JNIEnv *env, jobject self, jlong h) {
    int n = 0;
    void *b = CRMDesktopPoll(h, &n);
    if (!b) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, n, b);
    free(b);
    return out;
}

JNIEXPORT jboolean JNICALL Java_com_codexrm_mobile_NativeP2P_sendDesktop(JNIEnv *env, jobject self, jlong h, jbyteArray data) {
    jbyte *b = (*env)->GetByteArrayElements(env, data, NULL);
    if (!b) return JNI_FALSE;
    jboolean sent = CRMDesktopSend(h, b, (*env)->GetArrayLength(env, data)) ? JNI_TRUE : JNI_FALSE;
    (*env)->ReleaseByteArrayElements(env, data, b, JNI_ABORT);
    return sent;
}

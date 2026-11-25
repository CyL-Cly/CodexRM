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

JNIEXPORT jlong JNICALL Java_com_codexrm_mobile_NativeP2P_measureLatency(JNIEnv *env, jobject self, jlong h) {
    return CRMMeasureLatency(h);
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

JNIEXPORT jboolean JNICALL Java_com_codexrm_mobile_NativeP2P_download(JNIEnv *env, jobject self, jlong h, jbyteArray id, jbyteArray path, jlong size, jboolean phone) {
    jsize id_len = (*env)->GetArrayLength(env, id);
    jsize path_len = (*env)->GetArrayLength(env, path);
    jbyte id_bytes[36], path_bytes[4096];
    if (id_len != sizeof(id_bytes) || path_len <= 0 || path_len > sizeof(path_bytes)) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, id, 0, id_len, id_bytes);
    (*env)->GetByteArrayRegion(env, path, 0, path_len, path_bytes);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    return CRMDownload(h, id_bytes, id_len, path_bytes, path_len, size, phone) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_codexrm_mobile_NativeP2P_cancelDownload(JNIEnv *env, jobject self, jlong h, jbyteArray id) {
    jsize len = (*env)->GetArrayLength(env, id);
    jbyte bytes[36];
    if (len != sizeof(bytes)) return;
    (*env)->GetByteArrayRegion(env, id, 0, len, bytes);
    if (!(*env)->ExceptionCheck(env)) CRMCancelDownload(h, bytes, len);
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

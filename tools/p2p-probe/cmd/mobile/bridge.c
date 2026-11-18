//go:build android

#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

JNIEXPORT jstring JNICALL Java_com_codexrm_p2pprobe_MainActivity_runProbe(JNIEnv *env, jclass klass, jstring input) {
    const char *config = (*env)->GetStringUTFChars(env, input, NULL);
    char *result = RunProbe((char *)config);
    (*env)->ReleaseStringUTFChars(env, input, config);
    jstring output = (*env)->NewStringUTF(env, result);
    free(result);
    return output;
}

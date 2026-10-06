// mHook 运行时防护（native）：常驻线程检测 调试器 / Frida / Hook 库 / Root。
// flags: 1=root 2=emu 4=frida 8=debug；action: 0=退出(kill) 其它=仅日志
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <signal.h>
#include <sys/stat.h>
#include <android/log.h>

#define TAG "mhook.rasp"

static int g_flags = 0;
static int g_action = 1;
static volatile int g_stop = 0;

static int tracer_pid(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    int pid = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) { pid = atoi(line + 10); break; }
    }
    fclose(f);
    return pid;
}

static int maps_hit(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    int hit = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "frida") || strstr(line, "gadget") || strstr(line, "libsubstrate")
                || strstr(line, "libdobby") || strstr(line, "sandhook") || strstr(line, "whale")
                || strstr(line, "xposed") || strstr(line, "lsposed") || strstr(line, "zygisk")
                || strstr(line, "shadowhook") || strstr(line, "lsplant") || strstr(line, "libpine")) {
            hit = 1; break;
        }
    }
    fclose(f);
    return hit;
}

static int rooted(void) {
    const char *p[] = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                       "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ksud", "/sbin/.magisk"};
    for (unsigned i = 0; i < sizeof(p) / sizeof(p[0]); i++) {
        struct stat st;
        if (stat(p[i], &st) == 0) return 1;
    }
    return 0;
}

static void report(const char *what) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "risk: %s", what);
    if (g_action == 0) {
        kill(getpid(), SIGKILL);
    }
}

static void *worker(void *arg) {
    (void) arg;
    for (int round = 0; round < 20 && !g_stop; round++) {
        if ((g_flags & 8) && (tracer_pid() != 0)) { report("debug"); if (g_action == 0) return NULL; }
        if ((g_flags & 4) && maps_hit()) { report("frida/hook"); if (g_action == 0) return NULL; }
        if ((g_flags & 1) && rooted()) { report("root"); if (g_action == 0) return NULL; }
        usleep(2000 * 1000);
    }
    return NULL;
}

JNIEXPORT void JNICALL
Java_cn_mhook_protect_rt_Rasp_nativeStart(JNIEnv *env, jclass clazz, jint flags, jint action) {
    g_flags = flags;
    g_action = action;
    __android_log_print(ANDROID_LOG_INFO, TAG, "start flags=%d action=%d", flags, action);
    pthread_t t;
    if (pthread_create(&t, NULL, worker, NULL) == 0) pthread_detach(t);
}

static const JNINativeMethod METHODS[] = {
        {"nativeStart", "(II)V", (void *) Java_cn_mhook_protect_rt_Rasp_nativeStart}
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "cn/mhook/protect/rt/Rasp");
    if (cls != NULL) (*env)->RegisterNatives(env, cls, METHODS, (jint) (sizeof(METHODS) / sizeof(METHODS[0])));
    return JNI_VERSION_1_6;
}

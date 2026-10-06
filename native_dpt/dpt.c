// mHook DPT 密钥派生（native）：key = SHA256(master(32) || salt)[0..16]
// 主密钥以 (^0xA5) 混淆形式存放，运行期还原；APK 内不出现密钥。
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#ifdef DPT_TEST
#include <stdio.h>
typedef long long jlong;
#else
#include <jni.h>
#endif

typedef struct { uint32_t h[8]; uint64_t len; uint8_t buf[64]; size_t n; } sha256_ctx;

static const uint32_t KT[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

#define ROTR(x,n) (((x)>>(n))|((x)<<(32-(n))))
#define CH(x,y,z)  (((x)&(y))^((~(x))&(z)))
#define MAJ(x,y,z) (((x)&(y))^((x)&(z))^((y)&(z)))
#define BS0(x) (ROTR(x,2)^ROTR(x,13)^ROTR(x,22))
#define BS1(x) (ROTR(x,6)^ROTR(x,11)^ROTR(x,25))
#define SS0(x) (ROTR(x,7)^ROTR(x,18)^((x)>>3))
#define SS1(x) (ROTR(x,17)^ROTR(x,19)^((x)>>10))

static uint32_t be32(const uint8_t *p) {
    return ((uint32_t) p[0] << 24) | ((uint32_t) p[1] << 16) | ((uint32_t) p[2] << 8) | (uint32_t) p[3];
}

static void sha256_init(sha256_ctx *c) {
    c->h[0]=0x6a09e667; c->h[1]=0xbb67ae85; c->h[2]=0x3c6ef372; c->h[3]=0xa54ff53a;
    c->h[4]=0x510e527f; c->h[5]=0x9b05688c; c->h[6]=0x1f83d9ab; c->h[7]=0x5be0cd19;
    c->len = 0; c->n = 0;
}

static void sha256_block(sha256_ctx *c, const uint8_t *p) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) w[i] = be32(p + i * 4);
    for (int i = 16; i < 64; i++) w[i] = SS1(w[i-2]) + w[i-7] + SS0(w[i-15]) + w[i-16];
    uint32_t a=c->h[0],b=c->h[1],d=c->h[3],e=c->h[4],f=c->h[5],g=c->h[6],hh=c->h[7],cc=c->h[2];
    for (int i = 0; i < 64; i++) {
        uint32_t t1 = hh + BS1(e) + CH(e,f,g) + KT[i] + w[i];
        uint32_t t2 = BS0(a) + MAJ(a,b,cc);
        hh=g; g=f; f=e; e=d+t1; d=cc; cc=b; b=a; a=t1+t2;
    }
    c->h[0]+=a; c->h[1]+=b; c->h[2]+=cc; c->h[3]+=d; c->h[4]+=e; c->h[5]+=f; c->h[6]+=g; c->h[7]+=hh;
}

static void sha256_update(sha256_ctx *c, const uint8_t *p, size_t len) {
    c->len += (uint64_t) len * 8;
    while (len > 0) {
        size_t take = 64 - c->n;
        if (take > len) take = len;
        memcpy(c->buf + c->n, p, take);
        c->n += take; p += take; len -= take;
        if (c->n == 64) { sha256_block(c, c->buf); c->n = 0; }
    }
}

static void sha256_final(sha256_ctx *c, uint8_t out[32]) {
    uint64_t bits = c->len;
    uint8_t pad = 0x80;
    sha256_update(c, &pad, 1);
    uint8_t z = 0;
    while (c->n != 56) sha256_update(c, &z, 1);
    uint8_t lb[8];
    for (int i = 0; i < 8; i++) lb[i] = (uint8_t) (bits >> (56 - i * 8));
    sha256_update(c, lb, 8);
    for (int i = 0; i < 8; i++) {
        out[i*4]   = (uint8_t)(c->h[i] >> 24);
        out[i*4+1] = (uint8_t)(c->h[i] >> 16);
        out[i*4+2] = (uint8_t)(c->h[i] >> 8);
        out[i*4+3] = (uint8_t)(c->h[i]);
    }
}

// 主密钥（raw）以 ^0xA5 形式存放
static const uint8_t MASTER_OB[32] = {
    0xc8,0xed,0xca,0xca,0xce,0xe1,0xd5,0xd1,0xee,0xc0,0xdc,0x94,0xf1,0xca,0x96,0x97,
    0x9c,0xc4,0xc7,0xc6,0xc1,0xc0,0xc3,0xc2,0xcd,0xcc,0xcf,0xce,0xc9,0xc8,0xcb,0xca
};

static void key_from_salt(const uint8_t *salt, int slen, uint8_t out16[16]) {
    uint8_t master[32];
    for (int i = 0; i < 32; i++) master[i] = (uint8_t) (MASTER_OB[i] ^ 0xA5);
    sha256_ctx c;
    sha256_init(&c);
    sha256_update(&c, master, 32);
    if (salt && slen > 0) sha256_update(&c, salt, (size_t) slen);
    uint8_t d[32];
    sha256_final(&c, d);
    memcpy(out16, d, 16);
}

#ifndef DPT_TEST
JNIEXPORT jbyteArray JNICALL
Java_cn_mhook_protect_rt_DptKey_nativeKey(JNIEnv *env, jclass clazz, jbyteArray salt) {
    uint8_t out[16];
    if (salt != NULL) {
        jsize slen = (*env)->GetArrayLength(env, salt);
        jbyte *s = (*env)->GetByteArrayElements(env, salt, NULL);
        key_from_salt((const uint8_t *) s, (int) slen, out);
        if (s) (*env)->ReleaseByteArrayElements(env, salt, s, JNI_ABORT);
    } else {
        key_from_salt(NULL, 0, out);
    }
    jbyteArray r = (*env)->NewByteArray(env, 16);
    if (r) (*env)->SetByteArrayRegion(env, r, 0, 16, (const jbyte *) out);
    return r;
}

static const JNINativeMethod METHODS[] = {
        {"nativeKey", "([B)[B", (void *) Java_cn_mhook_protect_rt_DptKey_nativeKey}
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "cn/mhook/protect/rt/DptKey");
    if (cls != NULL) (*env)->RegisterNatives(env, cls, METHODS, (jint) (sizeof(METHODS) / sizeof(METHODS[0])));
    return JNI_VERSION_1_6;
}
#endif

#ifdef DPT_TEST
int main(void) {
    // SHA256("abc") 已知向量
    sha256_ctx c; sha256_init(&c);
    sha256_update(&c, (const uint8_t *) "abc", 3);
    uint8_t d[32]; sha256_final(&c, d);
    const uint8_t want[32] = {
        0xba,0x78,0x16,0xbf,0x8f,0x01,0xcf,0xea,0x41,0x41,0x40,0xde,0x5d,0xae,0x22,0x23,
        0xb0,0x03,0x61,0xa3,0x96,0x17,0x7a,0x9c,0xb4,0x10,0xff,0x61,0xf2,0x00,0x15,0xad
    };
    int ok = (memcmp(d, want, 32) == 0);
    printf("sha256(abc) %s\n", ok ? "OK" : "FAIL");
    uint8_t salt[8] = {1,2,3,4,5,6,7,8};
    uint8_t k[16]; key_from_salt(salt, 8, k);
    printf("key = ");
    for (int i = 0; i < 16; i++) printf("%02x", k[i]);
    printf("\n");
    return 0;
}
#endif

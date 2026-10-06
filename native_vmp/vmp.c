// mHook VMP 最小虚拟机（native 解释器）
// Java: cn.mhook.protect.rt.Vmp.nativeRun(byte[] bundle, int[] args) -> int
//   bundle = [n:1] 后跟 n 段 [len:4 LE][program...]；program 头 2 字节 = regCount,paramBase。
// 支持方法间互调（OP_CALL）。寄存器按“窗口”分配：第 i 个程序用 regs[i*64 .. ]。

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#ifdef VMP_TEST
typedef long long jlong;
#else
#include <jni.h>
#endif

// ---- SHA-256（用于 vmp 程序包密钥派生/解密） ----
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
static uint32_t be32(const uint8_t *p){ return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3]; }
static void sha_init(sha256_ctx *c){ c->h[0]=0x6a09e667;c->h[1]=0xbb67ae85;c->h[2]=0x3c6ef372;c->h[3]=0xa54ff53a;c->h[4]=0x510e527f;c->h[5]=0x9b05688c;c->h[6]=0x1f83d9ab;c->h[7]=0x5be0cd19;c->len=0;c->n=0; }
static void sha_block(sha256_ctx *c, const uint8_t *p){
    uint32_t w[64];
    for(int i=0;i<16;i++) w[i]=be32(p+i*4);
    for(int i=16;i<64;i++) w[i]=SS1(w[i-2])+w[i-7]+SS0(w[i-15])+w[i-16];
    uint32_t a=c->h[0],b=c->h[1],cc=c->h[2],d=c->h[3],e=c->h[4],f=c->h[5],g=c->h[6],hh=c->h[7];
    for(int i=0;i<64;i++){ uint32_t t1=hh+BS1(e)+CH(e,f,g)+KT[i]+w[i]; uint32_t t2=BS0(a)+MAJ(a,b,cc); hh=g;g=f;f=e;e=d+t1;d=cc;cc=b;b=a;a=t1+t2; }
    c->h[0]+=a;c->h[1]+=b;c->h[2]+=cc;c->h[3]+=d;c->h[4]+=e;c->h[5]+=f;c->h[6]+=g;c->h[7]+=hh;
}
static void sha_update(sha256_ctx *c,const uint8_t*p,size_t len){ c->len+=(uint64_t)len*8; while(len>0){ size_t t=64-c->n; if(t>len)t=len; memcpy(c->buf+c->n,p,t); c->n+=t;p+=t;len-=t; if(c->n==64){sha_block(c,c->buf);c->n=0;} } }
static void sha_final(sha256_ctx *c,uint8_t out[32]){ uint64_t bits=c->len; uint8_t pad=0x80,z=0; sha_update(c,&pad,1); while(c->n!=56) sha_update(c,&z,1); uint8_t lb[8]; for(int i=0;i<8;i++) lb[i]=(uint8_t)(bits>>(56-i*8)); sha_update(c,lb,8); for(int i=0;i<8;i++){ out[i*4]=(uint8_t)(c->h[i]>>24);out[i*4+1]=(uint8_t)(c->h[i]>>16);out[i*4+2]=(uint8_t)(c->h[i]>>8);out[i*4+3]=(uint8_t)c->h[i]; } }

static const uint8_t VMP_MASTER_OB[32] = {
    0xc8,0xed,0xca,0xca,0xce,0xe1,0xd5,0xd1,0xee,0xc0,0xdc,0x94,0xf1,0xca,0x96,0x97,
    0x9c,0xc4,0xc7,0xc6,0xc1,0xc0,0xc3,0xc2,0xcd,0xcc,0xcf,0xce,0xc9,0xc8,0xcb,0xca
};
static void vmp_key(const uint8_t *salt,int slen,uint8_t k[16]){
    uint8_t m[32]; for(int i=0;i<32;i++) m[i]=(uint8_t)(VMP_MASTER_OB[i]^0xA5);
    sha256_ctx c; sha_init(&c); sha_update(&c,m,32); if(salt&&slen>0) sha_update(&c,salt,(size_t)slen);
    uint8_t d[32]; sha_final(&c,d); memcpy(k,d,16);
}
// blob = salt(16) || (plain XOR keystream); 返回解密后的明文（malloc）
static uint8_t* vmp_decrypt(const uint8_t *blob,int len,int *outlen){
    if(len<16) return NULL;
    uint8_t k[16]; vmp_key(blob,16,k);
    int pl=len-16; uint8_t* out=(uint8_t*)malloc(pl>0?pl:1);
    if(!out) return NULL;
    sha256_ctx c; int off=0,ctr=0;
    while(off<pl){ c.len=0;c.n=0; sha_init(&c); sha_update(&c,k,16); uint8_t cb[4]={(uint8_t)ctr,(uint8_t)(ctr>>8),(uint8_t)(ctr>>16),(uint8_t)(ctr>>24)}; sha_update(&c,cb,4); uint8_t b[32]; sha_final(&c,b); int cc=(pl-off<32)?(pl-off):32; for(int i=0;i<cc;i++) out[off+i]=(uint8_t)(blob[16+off+i]^b[i]); off+=cc; ctr++; }
    *outlen=pl; return out;
}

#define REGS 512
#define WINDOW 64
#define MAXP 8
#define MAXFRAME 64

static int64_t g_regs[REGS];
static int g_base = 0;
#define regs (g_regs + g_base)

static const uint8_t *g_progs[MAXP];
static int g_progs_len[MAXP];
static int g_progs_base[MAXP];
static int g_nprog = 0;
static int g_cur = 0;

struct Frame { int pc, len, base, cur, dst; };
static struct Frame g_frames[MAXFRAME];
static int g_sp = 0;

static const uint8_t *g_p;
static int g_len, g_pc;
static jlong g_ret;
#define RET64

enum {
    OP_END = 0x00,
    OP_CONST = 0x01, OP_MOVE = 0x02,
    OP_ADD = 0x03, OP_SUB = 0x04, OP_MUL = 0x05, OP_AND = 0x06, OP_OR = 0x07, OP_XOR = 0x08,
    OP_NEG = 0x09, OP_SHL = 0x0A, OP_SHR = 0x0B, OP_USHR = 0x0C,
    OP_EQ = 0x0D, OP_NE = 0x0E, OP_LT = 0x0F, OP_GE = 0x10, OP_GT = 0x11, OP_LE = 0x12,
    OP_NOT = 0x13,
    OP_IF_EQZ = 0x20, OP_IF_NEZ = 0x21, OP_IF_LTZ = 0x22, OP_IF_GEZ = 0x23, OP_IF_GTZ = 0x24, OP_IF_LEZ = 0x25,
    OP_GOTO = 0x26,
    OP_RET = 0x30, OP_RET_VOID = 0x31, OP_RET_LONG = 0x32,
    OP_IF_EQ = 0x40, OP_IF_NE = 0x41, OP_IF_LT = 0x42, OP_IF_GE = 0x43, OP_IF_GT = 0x44, OP_IF_LE = 0x45,
    OP_ADD_LIT = 0x50, OP_SUB_LIT = 0x51, OP_RSUB_LIT = 0x52, OP_MUL_LIT = 0x53,
    OP_AND_LIT = 0x54, OP_OR_LIT = 0x55, OP_XOR_LIT = 0x56, OP_SHL_LIT = 0x57, OP_SHR_LIT = 0x58, OP_USHR_LIT = 0x59,
    OP_ADD64 = 0x61, OP_SUB64 = 0x62, OP_MUL64 = 0x63, OP_AND64 = 0x64, OP_OR64 = 0x65, OP_XOR64 = 0x66,
    OP_SHL64 = 0x67, OP_SHR64 = 0x68, OP_USHR64 = 0x69, OP_NEG64 = 0x6A, OP_NOT64 = 0x6B, OP_CMP_LONG = 0x6C,
    OP_INT_TO_LONG = 0x6D, OP_LONG_TO_INT = 0x6E,
    OP_INT_TO_BYTE = 0x74, OP_INT_TO_CHAR = 0x75, OP_INT_TO_SHORT = 0x76,
    OP_NEWARRAY = 0x70, OP_AGET = 0x71, OP_APUT = 0x72, OP_ARRAYLEN = 0x73,
    OP_INVOKE_BUILTIN = 0x80, OP_FILLARRAY = 0x81, OP_CALL = 0x82,
    OP_CONST_STR = 0x90, OP_STR_LEN = 0x91, OP_STR_EQ = 0x92, OP_STR_CAT = 0x93,
    OP_STR_CHARAT = 0x94, OP_INT_PARSE = 0x95, OP_STR_VALOF = 0x96, OP_STR_CMP = 0x97
};

#define ARRAYS 64
static int64_t *arrays[ARRAYS];
static int64_t arrays_len[ARRAYS];

#define MAXSTR 256
static char *g_str[MAXSTR];
static int g_nstr = 0;

static void clear_strs(void) {
    for (int i = 0; i < g_nstr; i++) { if (g_str[i]) { free(g_str[i]); g_str[i] = NULL; } }
    g_nstr = 0;
}

static int add_str(const char *p, int len) {
    if (g_nstr >= MAXSTR) return -1;
    char *s = (char *) malloc(len + 1);
    if (!s) return -1;
    memcpy(s, p, len);
    s[len] = 0;
    g_str[g_nstr] = s;
    return g_nstr++;
}

static int32_t i32(int64_t v) { return (int32_t) v; }

static void run(void) {
    g_pc = 2;
    while (g_pc < g_len) {
        int base = g_pc;
        int op = g_p[g_pc++];
        switch (op) {
            case OP_END:
            case OP_RET_VOID:
                if (g_sp > 0) {
                    g_sp--;
                    struct Frame f = g_frames[g_sp];
                    g_base = f.base; g_p = g_progs[f.cur]; g_len = g_progs_len[f.cur]; g_cur = f.cur; g_pc = f.pc;
                    regs[f.dst] = 0;
                    break;
                }
                g_ret = 0; return;
            case OP_RET: {
                int r = g_p[g_pc++];
                int64_t v = (int64_t) i32(regs[r]);
                if (g_sp > 0) {
                    g_sp--;
                    struct Frame f = g_frames[g_sp];
                    g_base = f.base; g_p = g_progs[f.cur]; g_len = g_progs_len[f.cur]; g_cur = f.cur; g_pc = f.pc;
                    regs[f.dst] = v;
                    break;
                }
                g_ret = v; return;
            }
            case OP_RET_LONG: {
                int r = g_p[g_pc++];
                int64_t v = regs[r];
                if (g_sp > 0) {
                    g_sp--;
                    struct Frame f = g_frames[g_sp];
                    g_base = f.base; g_p = g_progs[f.cur]; g_len = g_progs_len[f.cur]; g_cur = f.cur; g_pc = f.pc;
                    regs[f.dst] = v;
                    break;
                }
                g_ret = v; return;
            }
            case OP_CALL: {
                int id = g_p[g_pc++], dst = g_p[g_pc++], argc = g_p[g_pc++];
                int64_t argv[8];
                for (int i = 0; i < argc && i < 8; i++) argv[i] = regs[g_p[g_pc++]];
                if (id < 0 || id >= g_nprog || g_sp >= MAXFRAME) { g_ret = 0; return; }
                struct Frame *f = &g_frames[g_sp++];
                f->pc = g_pc; f->len = g_len; f->base = g_base; f->cur = g_cur; f->dst = dst;
                g_cur = id; g_base = g_progs_base[id]; g_p = g_progs[id]; g_len = g_progs_len[id];
                int pb = g_p[1];
                for (int i = 0; i < argc && i < 8; i++) regs[pb + i] = argv[i];
                g_pc = 2;
                break;
            }
            case OP_CONST: { int r=g_p[g_pc++]; int64_t imm; memcpy(&imm,g_p+g_pc,8); g_pc+=8; if(r<REGS) regs[r]=imm; break; }
            case OP_MOVE: { int d=g_p[g_pc++],s=g_p[g_pc++]; if(d<REGS&&s<REGS) regs[d]=regs[s]; break; }
            case OP_ADD: case OP_SUB: case OP_MUL: case OP_AND: case OP_OR: case OP_XOR:
            case OP_SHL: case OP_SHR: case OP_USHR: {
                int d=g_p[g_pc++],a=g_p[g_pc++],b=g_p[g_pc++]; int32_t x=i32(regs[a]),y=i32(regs[b]),v;
                switch(op){case OP_ADD:v=x+y;break;case OP_SUB:v=x-y;break;case OP_MUL:v=x*y;break;
                case OP_AND:v=x&y;break;case OP_OR:v=x|y;break;case OP_XOR:v=x^y;break;
                case OP_SHL:v=x<<(y&31);break;case OP_SHR:v=x>>(y&31);break;
                default:v=(int32_t)(((uint32_t)x)>>(y&31));break;} regs[d]=v; break;
            }
            case OP_NEG: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=-i32(regs[a]); break; }
            case OP_NOT: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=~i32(regs[a]); break; }
            case OP_EQ: case OP_NE: case OP_LT: case OP_GE: case OP_GT: case OP_LE: {
                int d=g_p[g_pc++],a=g_p[g_pc++],b=g_p[g_pc++]; int32_t x=i32(regs[a]),y=i32(regs[b]); int v;
                switch(op){case OP_EQ:v=x==y;break;case OP_NE:v=x!=y;break;case OP_LT:v=x<y;break;
                case OP_GE:v=x>=y;break;case OP_GT:v=x>y;break;default:v=x<=y;break;} regs[d]=v; break;
            }
            case OP_IF_EQZ: case OP_IF_NEZ: case OP_IF_LTZ: case OP_IF_GEZ: case OP_IF_GTZ: case OP_IF_LEZ: {
                int r=g_p[g_pc++]; int16_t off; memcpy(&off,g_p+g_pc,2); g_pc+=2; int32_t v=i32(regs[r]); int t;
                switch(op){case OP_IF_EQZ:t=v==0;break;case OP_IF_NEZ:t=v!=0;break;case OP_IF_LTZ:t=v<0;break;
                case OP_IF_GEZ:t=v>=0;break;case OP_IF_GTZ:t=v>0;break;default:t=v<=0;break;}
                if(t) g_pc=base+off; break;
            }
            case OP_IF_EQ: case OP_IF_NE: case OP_IF_LT: case OP_IF_GE: case OP_IF_GT: case OP_IF_LE: {
                int a=g_p[g_pc++],b=g_p[g_pc++]; int16_t off; memcpy(&off,g_p+g_pc,2); g_pc+=2;
                int32_t x=i32(regs[a]),y=i32(regs[b]); int t;
                switch(op){case OP_IF_EQ:t=x==y;break;case OP_IF_NE:t=x!=y;break;case OP_IF_LT:t=x<y;break;
                case OP_IF_GE:t=x>=y;break;case OP_IF_GT:t=x>y;break;default:t=x<=y;break;}
                if(t) g_pc=base+off; break;
            }
            case OP_GOTO: { int16_t off; memcpy(&off,g_p+g_pc,2); g_pc+=2; g_pc=base+off; break; }
            case OP_ADD_LIT: case OP_SUB_LIT: case OP_RSUB_LIT: case OP_MUL_LIT:
            case OP_AND_LIT: case OP_OR_LIT: case OP_XOR_LIT: case OP_SHL_LIT: case OP_SHR_LIT: case OP_USHR_LIT: {
                int d=g_p[g_pc++],a=g_p[g_pc++]; int16_t lit; memcpy(&lit,g_p+g_pc,2); g_pc+=2;
                int32_t x=i32(regs[a]),l=lit,v;
                switch(op){case OP_ADD_LIT:v=x+l;break;case OP_SUB_LIT:v=x-l;break;case OP_RSUB_LIT:v=l-x;break;
                case OP_MUL_LIT:v=x*l;break;case OP_AND_LIT:v=x&l;break;case OP_OR_LIT:v=x|l;break;
                case OP_XOR_LIT:v=x^l;break;case OP_SHL_LIT:v=x<<(l&31);break;case OP_SHR_LIT:v=x>>(l&31);break;
                default:v=(int32_t)(((uint32_t)x)>>(l&31));break;} regs[d]=v; break;
            }
            case OP_ADD64: case OP_SUB64: case OP_MUL64: case OP_AND64: case OP_OR64:
            case OP_XOR64: case OP_SHL64: case OP_SHR64: case OP_USHR64: {
                int d=g_p[g_pc++],a=g_p[g_pc++],b=g_p[g_pc++]; int64_t x=regs[a],y=regs[b],v;
                switch(op){case OP_ADD64:v=x+y;break;case OP_SUB64:v=x-y;break;case OP_MUL64:v=x*y;break;
                case OP_AND64:v=x&y;break;case OP_OR64:v=x|y;break;case OP_XOR64:v=x^y;break;
                case OP_SHL64:v=x<<(y&63);break;case OP_SHR64:v=x>>(y&63);break;
                default:v=(int64_t)(((uint64_t)x)>>(y&63));break;} regs[d]=v; break;
            }
            case OP_NEG64: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=-regs[a]; break; }
            case OP_NOT64: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=~regs[a]; break; }
            case OP_CMP_LONG: { int d=g_p[g_pc++],a=g_p[g_pc++],b=g_p[g_pc++]; int64_t x=regs[a],y=regs[b]; regs[d]=(x<y)?-1:(x>y?1:0); break; }
            case OP_INT_TO_LONG: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=(int64_t)i32(regs[a]); break; }
            case OP_LONG_TO_INT: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=(int64_t)i32(regs[a]); break; }
            case OP_INT_TO_BYTE: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=(int32_t)(int8_t)i32(regs[a]); break; }
            case OP_INT_TO_CHAR: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=(int32_t)(uint16_t)i32(regs[a]); break; }
            case OP_INT_TO_SHORT: { int d=g_p[g_pc++],a=g_p[g_pc++]; regs[d]=(int32_t)(int16_t)i32(regs[a]); break; }
            case OP_NEWARRAY: {
                int d=g_p[g_pc++],lr=g_p[g_pc++]; int32_t n=(lr<REGS)?i32(regs[lr]):0; if(n<0)n=0;
                int id=-1; for(int i=0;i<ARRAYS;i++) if(arrays[i]==NULL){id=i;break;} if(id<0)id=0;
                if(arrays[id]){free(arrays[id]);arrays[id]=NULL;}
                arrays[id]=(int64_t*)malloc(sizeof(int64_t)*(n>0?n:1));
                if(arrays[id]) for(int i=0;i<n;i++) arrays[id][i]=0;
                arrays_len[id]=n; regs[d]=id; break;
            }
            case OP_AGET: { int d=g_p[g_pc++],a=g_p[g_pc++],i=g_p[g_pc++]; int id=i32(regs[a]),idx=i32(regs[i]);
                regs[d]=(id>=0&&id<ARRAYS&&arrays[id]&&idx>=0&&idx<arrays_len[id])?arrays[id][idx]:0; break; }
            case OP_APUT: { int a=g_p[g_pc++],i=g_p[g_pc++],v=g_p[g_pc++]; int id=i32(regs[a]),idx=i32(regs[i]);
                if(id>=0&&id<ARRAYS&&arrays[id]&&idx>=0&&idx<arrays_len[id]) arrays[id][idx]=regs[v]; break; }
            case OP_ARRAYLEN: { int d=g_p[g_pc++],a=g_p[g_pc++]; int id=i32(regs[a]); regs[d]=(id>=0&&id<ARRAYS)?arrays_len[id]:0; break; }
            case OP_FILLARRAY: {
                int r=g_p[g_pc++]; int cnt=(g_p[g_pc]&0xFF)|((g_p[g_pc+1]&0xFF)<<8); g_pc+=2; g_pc++;
                int id=i32(regs[r]);
                for(int i=0;i<cnt;i++){ int64_t v; memcpy(&v,g_p+g_pc,8); g_pc+=8; if(id>=0&&id<ARRAYS&&arrays[id]&&i<arrays_len[id]) arrays[id][i]=v; }
                break;
            }
            case OP_INVOKE_BUILTIN: {
                int bid=g_p[g_pc++],dst=g_p[g_pc++],argc=g_p[g_pc++];
                int64_t av[8],raw[8];
                for(int i=0;i<argc&&i<8;i++){ raw[i]=regs[g_p[g_pc++]]; av[i]=i32(raw[i]); }
                int isVoid=0; int64_t r=0;
                switch(bid){
                    case 0: r=av[0]<0?-av[0]:av[0]; break;
                    case 1: r=av[0]<av[1]?av[0]:av[1]; break;
                    case 2: r=av[0]>av[1]?av[0]:av[1]; break;
                    case 3: r=raw[0]<0?-raw[0]:raw[0]; break;
                    case 4: r=raw[0]<raw[1]?raw[0]:raw[1]; break;
                    case 5: r=raw[0]>raw[1]?raw[0]:raw[1]; break;
                    case 6: { uint32_t x=(uint32_t)av[0]; int c=0; while(x){x&=x-1;c++;} r=c; } break;
                    case 7: { uint64_t x=(uint64_t)raw[0]; int c=0; while(x){x&=x-1;c++;} r=c; } break;
                    case 8: { int sid=i32(raw[0]),sp=i32(raw[1]),did=i32(raw[2]),dp=i32(raw[3]),ln=i32(raw[4]);
                        if(sid>=0&&sid<ARRAYS&&did>=0&&did<ARRAYS&&arrays[sid]&&arrays[did]) for(int i=0;i<ln;i++){int s=sp+i,d=dp+i; if(s>=0&&s<arrays_len[sid]&&d>=0&&d<arrays_len[did]) arrays[did][d]=arrays[sid][s];} isVoid=1; } break;
                    default: r=0;
                }
                if(!isVoid) regs[dst]=r;
                break;
            }
            case OP_CONST_STR: {
                int r = g_p[g_pc++];
                int ln = (g_p[g_pc] & 0xFF) | ((g_p[g_pc + 1] & 0xFF) << 8);
                g_pc += 2;
                int id = add_str((const char *) (g_p + g_pc), ln);
                g_pc += ln;
                regs[r] = id;
                break;
            }
            case OP_STR_LEN: { int d=g_p[g_pc++],a=g_p[g_pc++]; int id=i32(regs[a]); regs[d]=(id>=0&&id<g_nstr&&g_str[id])?(int64_t)strlen(g_str[id]):0; break; }
            case OP_STR_EQ: { int d=g_p[g_pc++],a=g_p[g_pc++],b=g_p[g_pc++]; int x=i32(regs[a]),y=i32(regs[b]); int v=0;
                if(x>=0&&x<g_nstr&&y>=0&&y<g_nstr&&g_str[x]&&g_str[y]) v=(strcmp(g_str[x],g_str[y])==0); regs[d]=v; break; }
            case OP_STR_CMP: { int d=g_p[g_pc++],a=g_p[g_pc++],b=g_p[g_pc++]; int x=i32(regs[a]),y=i32(regs[b]); int v=0;
                if(x>=0&&x<g_nstr&&y>=0&&y<g_nstr&&g_str[x]&&g_str[y]){int c=strcmp(g_str[x],g_str[y]); v=c<0?-1:(c>0?1:0);} regs[d]=v; break; }
            case OP_STR_CAT: { int d=g_p[g_pc++],a=g_p[g_pc++],b=g_p[g_pc++]; int x=i32(regs[a]),y=i32(regs[b]);
                if(x>=0&&x<g_nstr&&y>=0&&y<g_nstr&&g_str[x]&&g_str[y]){ int lx=(int)strlen(g_str[x]),ly=(int)strlen(g_str[y]);
                    char* t=(char*)malloc(lx+ly+1); memcpy(t,g_str[x],lx); memcpy(t+lx,g_str[y],ly); t[lx+ly]=0;
                    int id=add_str(t,lx+ly); free(t); regs[d]=id; } else regs[d]=-1; break; }
            case OP_STR_CHARAT: { int d=g_p[g_pc++],a=g_p[g_pc++],i=g_p[g_pc++]; int id=i32(regs[a]),idx=i32(regs[i]);
                regs[d]=(id>=0&&id<g_nstr&&g_str[id]&&idx>=0&&(size_t)idx<strlen(g_str[id]))?(unsigned char)g_str[id][idx]:-1; break; }
            case OP_INT_PARSE: { int d=g_p[g_pc++],a=g_p[g_pc++]; int id=i32(regs[a]); regs[d]=(id>=0&&id<g_nstr&&g_str[id])?(int64_t)strtol(g_str[id],NULL,10):0; break; }
            case OP_STR_VALOF: { int d=g_p[g_pc++],a=g_p[g_pc++]; char buf[24]; int n=snprintf(buf,sizeof(buf),"%d",i32(regs[a])); regs[d]=add_str(buf,n); break; }
            default: g_ret=0; return;
        }
    }
    g_ret = 0;
}

static void clear_arrays(void) {
    for (int i = 0; i < ARRAYS; i++) { if (arrays[i]) { free(arrays[i]); arrays[i] = NULL; } arrays_len[i] = 0; }
}

// 解析 bundle，执行第 0 个程序
static jlong run_bundle(const uint8_t *b, int len) {
    g_nprog = 0; g_sp = 0; g_ret = 0; g_base = 0;
    clear_arrays();
    clear_strs();
    memset(g_regs, 0, sizeof(g_regs));
    if (len < 1) return 0;
    int n = b[0];
    if (n <= 0) return 0;
    if (n > MAXP) n = MAXP;
    int off = 1;
    for (int i = 0; i < n; i++) {
        if (off + 4 > len) { g_nprog = i; break; }
        int plen = (b[off]) | (b[off + 1] << 8) | (b[off + 2] << 16) | (b[off + 3] << 24);
        off += 4;
        if (plen <= 0 || off + plen > len) { g_nprog = i; break; }
        g_progs[i] = b + off;
        g_progs_len[i] = plen;
        g_progs_base[i] = i * WINDOW;
        off += plen;
        g_nprog = i + 1;
    }
    if (g_nprog <= 0) return 0;
    return g_nprog;
}

#ifndef VMP_TEST
static jint run_java(JNIEnv *env, jbyteArray prog, jint id, jintArray args) {
    if (prog == NULL) return 0;
    jsize plen = (*env)->GetArrayLength(env, prog);
    if (plen <= 1) return 0;
    jbyte *p = (*env)->GetByteArrayElements(env, prog, NULL);
    if (p == NULL) return 0;
    uint8_t *copy = (uint8_t *) malloc(plen);
    if (copy == NULL) { (*env)->ReleaseByteArrayElements(env, prog, p, JNI_ABORT); return 0; }
    memcpy(copy, p, plen);
    (*env)->ReleaseByteArrayElements(env, prog, p, JNI_ABORT);

    int outlen = 0;
    uint8_t *plain = vmp_decrypt(copy, (int) plen, &outlen);
    uint8_t *use = plain ? plain : copy;
    int uselen = plain ? outlen : (int) plen;
    run_bundle(use, uselen);
    if (id < 0 || id >= g_nprog) id = 0;
    if (g_nprog <= 0) { free(copy); return 0; }
    if (args != NULL) {
        jsize alen = (*env)->GetArrayLength(env, args);
        jint *a = (*env)->GetIntArrayElements(env, args, NULL);
        if (a != NULL) {
            int pb = (g_progs_len[id] >= 2) ? (int) (uint8_t) g_progs[id][1] : 0;
            for (jsize i = 0; i < alen && i < REGS; i++) {
                int idx = g_progs_base[id] + pb + (int) i;
                if (idx >= 0 && idx < REGS) g_regs[idx] = (int32_t) a[i];
            }
            (*env)->ReleaseIntArrayElements(env, args, a, JNI_ABORT);
        }
    }
    g_cur = id; g_base = g_progs_base[id]; g_p = g_progs[id]; g_len = g_progs_len[id];
    run();
    free(plain);
    free(copy);
    return (jint) g_ret;
}

JNIEXPORT jint JNICALL
Java_cn_mhook_protect_rt_Vmp_nativeRun(JNIEnv *env, jclass clazz, jbyteArray prog, jint id, jintArray args) {
    return run_java(env, prog, id, args);
}

JNIEXPORT jlong JNICALL
Java_cn_mhook_protect_rt_Vmp_nativeRunL(JNIEnv *env, jclass clazz, jbyteArray prog, jint id, jintArray args) {
    run_java(env, prog, id, args);
    return g_ret;
}

static const JNINativeMethod METHODS[] = {
        {"nativeRun", "([BI[I)I", (void *) Java_cn_mhook_protect_rt_Vmp_nativeRun},
        {"nativeRunL", "([BI[I)J", (void *) Java_cn_mhook_protect_rt_Vmp_nativeRunL}
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "cn/mhook/protect/rt/Vmp");
    if (cls != NULL) (*env)->RegisterNatives(env, cls, METHODS, (jint) (sizeof(METHODS) / sizeof(METHODS[0])));
    return JNI_VERSION_1_6;
}
#endif

#ifdef VMP_TEST
static void test_one(const char *name, const uint8_t *p, int len, int64_t expect) {
    int64_t r = run_bundle(p, len);
    // 单程序：p 不是 bundle -> 这里用手工 bundle 调用，故此处仅占位
    (void) r; (void) name; (void) expect;
}
int main(int argc, char **argv) {
    if (argc >= 2) {
        FILE *f = fopen(argv[1], "rb");
        if (!f) { printf("open fail\n"); return 1; }
        static uint8_t buf[65536];
        int len = (int) fread(buf, 1, sizeof(buf), f);
        fclose(f);
        int id = (argc >= 3) ? atoi(argv[2]) : 0;
        int n = run_bundle(buf, len);
        if (id < 0 || id >= n) id = 0;
        if (n > 0) { g_cur = id; g_base = g_progs_base[id]; g_p = g_progs[id]; g_len = g_progs_len[id]; run(); }
        printf("file result=%lld nprog=%d len=%d\n", (long long) g_ret, n, len);
        return 0;
    }
    // bundle: n=2；prog0 调用 prog1
    // prog0: 8,0, const r0=10, const r1=20, CALL id1 dst2 argc2 (r0,r1), RET r2, END
    uint8_t prog0[] = {
        8,0,
        0x01,0, 10,0,0,0,0,0,0,0,
        0x01,1, 20,0,0,0,0,0,0,0,
        0x82, 1, 2, 2, 0, 1,
        0x30, 2,
        0x00
    };
    // prog1: 8,4, ADD r0=r4+r5, RET r0, END
    uint8_t prog1[] = { 8,4, 0x03,0,4,5, 0x30,0, 0x00 };
    int l0 = (int) sizeof(prog0), l1 = (int) sizeof(prog1);
    uint8_t bundle[128];
    int o = 0;
    bundle[o++] = 2;
    bundle[o++] = l0 & 0xFF; bundle[o++] = (l0>>8)&0xFF; bundle[o++] = 0; bundle[o++] = 0;
    memcpy(bundle+o, prog0, l0); o += l0;
    bundle[o++] = l1 & 0xFF; bundle[o++] = (l1>>8)&0xFF; bundle[o++] = 0; bundle[o++] = 0;
    memcpy(bundle+o, prog1, l1); o += l1;
    int n = run_bundle(bundle, o);
    g_cur = 0; g_base = g_progs_base[0]; g_p = g_progs[0]; g_len = g_progs_len[0];
    run();
    printf("call result=%lld (expect 30) nprog=%d\n", (long long) g_ret, n);

    // 字符串：const "abcdef" 的 length = 6
    {
        uint8_t s1[] = { 8,0, 0x90,0, 6,0, 'a','b','c','d','e','f', 0x91,1,0, 0x30,1, 0x00 };
        uint8_t sb[64]; int so=0; int sl=(int)sizeof(s1);
        sb[so++]=1; sb[so++]=sl&0xFF; sb[so++]=(sl>>8)&0xFF; sb[so++]=0; sb[so++]=0; memcpy(sb+so,s1,sl); so+=sl;
        run_bundle(sb,so); g_cur=0; g_base=g_progs_base[0]; g_p=g_progs[0]; g_len=g_progs_len[0]; run();
        printf("strlen result=%lld (expect 6)\n",(long long)g_ret);
    }
    // 字符串：parseInt("123") = 123
    {
        uint8_t s2[] = { 8,0, 0x90,0, 3,0,'1','2','3', 0x95,1,0, 0x30,1, 0x00 };
        uint8_t sb[64]; int so=0; int sl=(int)sizeof(s2);
        sb[so++]=1; sb[so++]=sl&0xFF; sb[so++]=(sl>>8)&0xFF; sb[so++]=0; sb[so++]=0; memcpy(sb+so,s2,sl); so+=sl;
        run_bundle(sb,so); g_cur=0; g_base=g_progs_base[0]; g_p=g_progs[0]; g_len=g_progs_len[0]; run();
        printf("parseint result=%lld (expect 123)\n",(long long)g_ret);
    }
    return 0;
}
#endif

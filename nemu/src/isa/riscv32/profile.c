#include <common.h>
#include <profile.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PHASE_LEN 16000000ull
#define MAX_PHASES 128
#define PC_SLOTS (1u << 20)

enum Kind { K_ALU, K_BRANCH, K_JAL, K_JALR, K_LOAD, K_STORE, K_M, K_B, K_SYSTEM, K_OTHER, K_NR };
enum MOp { M_MUL, M_MULH, M_MULHSU, M_MULHU, M_DIV, M_DIVU, M_REM, M_REMU, M_NR };
enum BOp { B_CLZ, B_CTZ, B_CPOP, B_CLMUL, B_ORCB, B_XPERM4, B_ROR, B_OTHER, B_NR };
typedef struct { uint32_t pc; uint64_t n, taken; } PcStat;
typedef struct { uint64_t inst, control, taken, load, store, raw; } Phase;
typedef struct { uint32_t tag, target; uint8_t valid, type, counter; } BtbEnt;

static const char *out_path;
static uint64_t total, kinds[K_NR], mops[M_NR], bops[B_NR], raw_dist[9], prod_cons[K_NR][K_NR];
static uint64_t widths[4], regions[5], aliases[512], jalr_rd[3], jalr_rs1[3];
static Phase phases[MAX_PHASES];
static PcStat *pcs;
static uint64_t btb16_miss, btb16_jalr_miss, btb32_jalr_miss;
static BtbEnt btb16[16], btb16j[16], btb32j[32];
static uint64_t last_write[32];
static uint8_t last_kind[32];

void riscv_profile_set_output(const char *path) { out_path = path; if (path && !pcs) pcs = calloc(PC_SLOTS, sizeof(*pcs)); }
bool riscv_profile_enabled(void) { return out_path != NULL; }

static unsigned kind_of(uint32_t x) {
  unsigned op=x&0x7f, f7=x>>25;
  if (op==0x63) return K_BRANCH;
  if (op==0x6f) return K_JAL;
  if (op==0x67) return K_JALR;
  if (op==0x03) return K_LOAD;
  if (op==0x23) return K_STORE;
  if (op==0x73) return K_SYSTEM;
  if (op==0x33 && f7==1) return K_M;
  if ((op==0x33 || op==0x13) && (f7==0x05 || f7==0x30 || f7==0x24 || f7==0x14 || f7==0x34)) return K_B;
  if (op==0x33 || op==0x13 || op==0x37 || op==0x17) return K_ALU;
  return K_OTHER;
}
static unsigned mop_of(uint32_t x) { return (x>>12)&7; }
static unsigned bop_of(uint32_t x) {
  unsigned op=x&0x7f, f3=(x>>12)&7, f7=x>>25, rs2=(x>>20)&31;
  if (op==0x13 && f3==1 && f7==0x30) { if(rs2==0) return B_CLZ; if(rs2==1)return B_CTZ; if(rs2==2)return B_CPOP; }
  if (op==0x33 && f7==0x05 && f3==1) return B_CLMUL;
  if (op==0x13 && f3==5 && f7==0x14 && rs2==7) return B_ORCB;
  if (op==0x33 && f7==0x14 && f3==2) return B_XPERM4;
  if ((op==0x33 && f7==0x30 && f3==5) || (op==0x13 && f7==0x30 && f3==5)) return B_ROR;
  return B_OTHER;
}
static int32_t sext(uint32_t x,unsigned n){ return (int32_t)(x<<(32-n))>>(32-n); }
static void model(BtbEnt *b,unsigned n,uint32_t pc,uint32_t target,unsigned type,
                  bool taken,bool allow_jalr,uint64_t *miss){
  unsigned i=(pc>>2)&(n-1);
  if (type==K_JALR && !allow_jalr) { (*miss)++; return; }
  bool hit=b[i].valid && b[i].tag==pc && b[i].type==type;
  bool predicted_taken=type==K_BRANCH ? hit && b[i].counter>=2 : hit;
  if (predicted_taken!=taken || (taken && (!hit || b[i].target!=target))) (*miss)++;
  if (!hit) b[i]=(BtbEnt){pc,target,1,type,taken?2:1};
  else {
    if (taken) { if (b[i].counter<3) b[i].counter++; b[i].target=target; }
    else if (b[i].counter>0) b[i].counter--;
  }
}
void riscv_profile_record(const Decode *s, word_t x, word_t rs1_before,
                          word_t rs2_before) {
  unsigned k=kind_of(x), op=x&0x7f, rd=(x>>7)&31, rs1=(x>>15)&31, rs2=(x>>20)&31;
  bool use1=!(op==0x37||op==0x17||op==0x6f), use2=(op==0x33||op==0x23||op==0x63);
  uint64_t seq=++total; kinds[k]++; Phase *p=&phases[(seq-1)/PHASE_LEN]; p->inst++;
  /* Multiplication is a permutation modulo 2^20. Workloads currently execute
   * within a <=4 MiB IROM window, so this dense cache is collision-free. */
  PcStat *q=&pcs[((s->pc>>2)*2654435761u)&(PC_SLOTS-1)]; if(!q->n||q->pc==s->pc){q->pc=s->pc;q->n++;}
  unsigned src[2]={rs1,rs2}; for(unsigned z=0;z<2;z++) if((z?use2:use1)&&src[z]&&last_write[src[z]]){uint64_t d=seq-last_write[src[z]];raw_dist[d<8?d:8]++;prod_cons[last_kind[src[z]]][k]++;p->raw++;}
  if(k==K_M) mops[mop_of(x)]++;
  if(k==K_B) bops[bop_of(x)]++;
  if(k==K_BRANCH||k==K_JAL||k==K_JALR){bool taken=s->dnpc!=s->snpc;p->control++;if(taken){p->taken++;q->taken++;}model(btb16,16,s->pc,s->dnpc,k,taken,false,&btb16_miss);model(btb16j,16,s->pc,s->dnpc,k,taken,true,&btb16_jalr_miss);model(btb32j,32,s->pc,s->dnpc,k,taken,true,&btb32_jalr_miss);}
  if(k==K_JALR){jalr_rd[rd==0?0:rd==1?1:2]++;jalr_rs1[rs1==1?0:rs1==5?1:2]++;}
  if(k==K_LOAD||k==K_STORE){int32_t imm=k==K_LOAD?sext(x>>20,12):sext(((x>>25)<<5)|((x>>7)&31),12);uint32_t a=rs1_before+imm;(void)rs2_before;unsigned f3=(x>>12)&7,w=(f3&3)==0?0:(f3&3)==1?1:2;widths[w]++;unsigned reg=a<0x10000000?0:a<0x80000000?1:a<0x90000000?2:a<0xc0000000?3:4;regions[reg]++;aliases[a&511]++;if(k==K_LOAD)p->load++;else p->store++;}
  bool writes=(k!=K_BRANCH&&k!=K_STORE&&op!=0x0f); if(writes&&rd){last_write[rd]=seq;last_kind[rd]=k;}
}
static void arr(FILE*f,const uint64_t*a,unsigned n){for(unsigned i=0;i<n;i++)fprintf(f,"%s%llu",i?",":"",(unsigned long long)a[i]);}
static int pc_cmp(const void *a,const void *b){const PcStat*x=a,*y=b;return x->n<y->n?1:x->n>y->n?-1:0;}
void riscv_profile_finish(void){if(!out_path)return;FILE*f=fopen(out_path,"w");if(!f){perror(out_path);return;}static const char*kn[]={"alu","branch","jal","jalr","load","store","m","b","system","other"};
 uint64_t control=kinds[K_BRANCH]+kinds[K_JAL]+kinds[K_JALR];
 fprintf(f,"{\n  \"schema\":1,\n  \"total_instructions\":%llu,\n  \"control_instructions\":%llu,\n  \"categories\":{",(unsigned long long)total,(unsigned long long)control);for(int i=0;i<K_NR;i++)fprintf(f,"%s\"%s\":%llu",i?",":"",kn[i],(unsigned long long)kinds[i]);fprintf(f,"},\n  \"m_ops_order\":[\"mul\",\"mulh\",\"mulhsu\",\"mulhu\",\"div\",\"divu\",\"rem\",\"remu\"],\n  \"m_ops\":[");arr(f,mops,M_NR);fprintf(f,"],\n  \"b_ops_order\":[\"clz\",\"ctz\",\"cpop\",\"clmul\",\"orc.b\",\"xperm4\",\"ror\",\"other\"],\n  \"b_ops\":[");arr(f,bops,B_NR);fprintf(f,"],\n  \"raw_distance_1_to_7_then_8plus\":[");arr(f,raw_dist+1,8);fprintf(f,"],\n  \"producer_consumer_category_matrix\":[");for(int i=0;i<K_NR;i++){if(i)fputc(',',f);fputc('[',f);arr(f,prod_cons[i],K_NR);fputc(']',f);}fprintf(f,"],\n  \"memory_width_byte_half_word_other\":[");arr(f,widths,4);fprintf(f,"],\n  \"memory_regions_lt10000000_lt80000000_lt90000000_ltc0000000_other\":[");arr(f,regions,5);fprintf(f,"],\n  \"jyd_low9_address_aliases\":[");arr(f,aliases,512);fprintf(f,"],\n  \"jalr_rd_x0_x1_other\":[");arr(f,jalr_rd,3);fprintf(f,"],\n  \"jalr_rs1_x1_x5_other\":[");arr(f,jalr_rs1,3);fprintf(f,"],\n  \"predictor_estimate\":{\"architectural_order\":true,\"note\":\"not a cycle-accurate model\",\"btb16_no_jalr_misses\":%llu,\"btb16_with_jalr_misses\":%llu,\"btb32_with_jalr_misses\":%llu},\n  \"phases_16m\":[",(unsigned long long)btb16_miss,(unsigned long long)btb16_jalr_miss,(unsigned long long)btb32_jalr_miss);unsigned np=(total+PHASE_LEN-1)/PHASE_LEN;for(unsigned i=0;i<np;i++)fprintf(f,"%s{\"inst\":%llu,\"control\":%llu,\"taken\":%llu,\"load\":%llu,\"store\":%llu,\"raw\":%llu}",i?",":"",(unsigned long long)phases[i].inst,(unsigned long long)phases[i].control,(unsigned long long)phases[i].taken,(unsigned long long)phases[i].load,(unsigned long long)phases[i].store,(unsigned long long)phases[i].raw);fprintf(f,"],\n  \"pc_hotspots\":[");qsort(pcs,PC_SLOTS,sizeof(*pcs),pc_cmp);for(unsigned i=0,n=0;i<PC_SLOTS&&n<64;i++)if(pcs[i].n){fprintf(f,"%s{\"pc\":\"0x%08x\",\"count\":%llu,\"taken\":%llu}",n++?",":"",pcs[i].pc,(unsigned long long)pcs[i].n,(unsigned long long)pcs[i].taken);}fprintf(f,"]\n}\n");fclose(f);free(pcs);pcs=NULL;out_path=NULL;}

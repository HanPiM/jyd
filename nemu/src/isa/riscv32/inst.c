/***************************************************************************************
* Copyright (c) 2014-2024 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include "common.h"
#include "local-include/reg.h"
#include "macro.h"
#include <assert.h>
#include <cpu/cpu.h>
#include <cpu/ifetch.h>
#include <cpu/decode.h>
#include <stdint.h>
#include <stdio.h>
#include <wchar.h>

#include <limits.h>

// We are in riscv32
#define signed_min INT_MIN
#define WORD_MAXBITLEN 32

#define R(i) gpr(i)
#define Mr vaddr_read
#define Mw vaddr_write

enum {
  TYPE_I, TYPE_U, TYPE_S,
  TYPE_J, TYPE_R,
  TYPE_B,
  TYPE_N, // none
};

#define src1R() do { *src1 = R(rs1); } while (0)
#define src2R() do { *src2 = R(rs2); } while (0)
#define immI() do { *imm = SEXT(BITS(i, 31, 20), 12); } while(0)
#define immU() do { *imm = SEXT(BITS(i, 31, 12), 20) << 12; } while(0)
#define immS() do { *imm = (SEXT(BITS(i, 31, 25), 7) << 5) | BITS(i, 11, 7); } while(0)
#define immB() do { *imm = SEXT((BITS(i,31,31)<<12)|(BITS(i,7,7)<<11)|(BITS(i,30,25)<<5)|(BITS(i,11,8)<<1),13);}while(0)

#define immJ() do{*imm=getimmJ(i);}while(0)

// J-immediate encodes a signed offset in multiples of 2 bytes
// imm[0]=0
static int getimmJ(uint32_t i){
	return SEXT(
		(BITS(i,31,31)<<19)
		|(BITS(i,19,12)<<11)
		|(BITS(i,20,20)<<10)
		|BITS(i,30,21),
		20
		)<<1;
}

static void decode_operand(Decode *s, int *rd, word_t *src1, word_t *src2, word_t *imm, int type) {
  uint32_t i = s->isa.inst;
  int rs1 = BITS(i, 19, 15);
  int rs2 = BITS(i, 24, 20);
  assert(rs2<32); // 5bit can't >= 32
				  // hence we can always get src2
  *rd     = BITS(i, 11, 7);
  switch (type) {
    case TYPE_I: src1R(); 		   immI(); break;
    case TYPE_U:                   immU(); break;
    case TYPE_S: src1R(); src2R(); immS(); break;
	case TYPE_J: immJ(); break;
	case TYPE_R: src1R(); src2R(); break; 
	case TYPE_B: src1R(); src2R(); immB(); break;
    case TYPE_N: break;
    default: panic("unsupported type = %d", type);
  }
  
    //  printf("  |decode:| rd %d r1 %d r2 %d imm %X(%d)\n",*rd,rs1,rs2,*imm,*imm);
}

// sign ext v with dynamic len
word_t d_sext(word_t v,int len){
	v &= (1U << len) - 1;
	if(v >> (len - 1))v|=~((1U << len) - 1);
	return v;
}

static int decode_exec(Decode *s) {
  s->dnpc = s->snpc;
  /*void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
  char buf[512];
  disassemble(buf,sizeof(buf),s->pc,(uint8_t*)&s->isa.inst,s->snpc-s->pc);
  printf(" |exec:%08X| %-25s \t",s->isa.inst,buf);
*/
#define INSTPAT_INST(s) ((s)->isa.inst)
#define INSTPAT_MATCH(s, name, type, ... /* execute body */ ) { \
  int rd = 0; \
  word_t src1 = 0, _src2 = 0, imm = 0; \
  decode_operand(s, &rd, &src1, &_src2, &imm, concat(TYPE_, type)); \
  __VA_ARGS__ ; \
}

  INSTPAT_START();
  INSTPAT("??????? ????? ????? ??? ????? 00101 11", auipc  , U, R(rd) = s->pc + imm);
  INSTPAT("??????? ????? ????? ??? ????? 01101 11", lui    , U, R(rd) = imm);

#define INSTPAT_I(s,name,...) INSTPAT(s,name,I,__VA_ARGS__)
#define INSTPAT_WITHSRC2(s,name,type,...) INSTPAT(s,name,type,word_t src2=_src2;__VA_ARGS__)
#define INSTPAT_S(s,name,...) INSTPAT_WITHSRC2(s,name,S,__VA_ARGS__)
#define INSTPAT_R(s,name,...) INSTPAT_WITHSRC2(s,name,R,__VA_ARGS__)

#define INSTPAT_B_IMM(func3t,name,cond)\
  INSTPAT_WITHSRC2("??????? ????? ????? "func3t" ????? 11000 11", name, B,\
		if(cond)s->dnpc=s->pc+imm); 

  INSTPAT_I("??????? ????? ????? 100 ????? 00000 11", lbu    , R(rd) = Mr(src1 + imm, 1));
  INSTPAT_I("??????? ????? ????? 010 ????? 00000 11", lw     , R(rd) = Mr(src1 + imm, 4));

  INSTPAT_S("??????? ????? ????? 000 ????? 01000 11", sb     , Mw(src1 + imm, 1, src2));
  INSTPAT_S("??????? ????? ????? 001 ????? 01000 11", sh     , Mw(src1 + imm, 2, src2));
  INSTPAT_S("??????? ????? ????? 010 ????? 01000 11", sw     , Mw(src1 + imm, 4, src2));

  INSTPAT_I("??????? ????? ????? 011 ????? 00100 11", sltiu  , R(rd) = (src1<imm)?1:0); 

  INSTPAT_R("0000000 ????? ????? 011 ????? 01100 11", sltu   , R(rd) = (src1<src2)?1:0); 

  word_t shamt=BITS(s->isa.inst,24,20);
  INSTPAT_I("0100000 ????? ????? 101 ????? 00100 11", srai   , R(rd) = d_sext(src1>>shamt,32-shamt)); 
  INSTPAT_I("0000000 ????? ????? 101 ????? 00100 11", srli   , R(rd) = src1 >> shamt);
  INSTPAT_I("0000000 ????? ????? 001 ????? 00100 11", slli   , R(rd) = src1 << shamt);

  INSTPAT_R("??????? ????? ????? 001 ????? 01100 11", sll    , R(rd)=src1<<BITS(src2,4,0));

  // BITS_I
  INSTPAT_I("??????? ????? ????? 111 ????? 00100 11", andi   , R(rd)=src1&imm);
  INSTPAT_I("??????? ????? ????? 100 ????? 00100 11", xori   , R(rd)=src1^imm);

  INSTPAT_I("??????? ????? ????? 000 ????? 00100 11", addi   , R(rd) = src1 + imm); 

  INSTPAT_R("0000000 ????? ????? 000 ????? 01100 11", add    , R(rd) = src1 + src2); 
  INSTPAT_R("0100000 ????? ????? 000 ????? 01100 11", sub    , R(rd) = src1 - src2); 
  INSTPAT_R("0000001 ????? ????? 000 ????? 01100 11", mul    , R(rd) = src1 * src2); 


  INSTPAT_R("0000001 ????? ????? 100 ????? 01100 11", div    ,
		  if(src2==0)R(rd)=-1;
		  else if(src1==signed_min&&src2==-1)R(rd)=signed_min;
		  else R(rd)=(sword_t)src1/(sword_t)src2;);
  INSTPAT_R("0000001 ????? ????? 110 ????? 01100 11", rem    ,
		  if(src2==0)R(rd)=src1;
		  else if(src1==((word_t)1<<(WORD_MAXBITLEN-1))&&src2==(~0))R(rd)=0;
		  else R(rd)=(sword_t)src1%(sword_t)src2;);

  INSTPAT_R("0000000 ????? ????? 111 ????? 01100 11", and    , R(rd) = src1 & src2); 
  INSTPAT_R("0000000 ????? ????? 110 ????? 01100 11", or     , R(rd) = src1 | src2); 
  INSTPAT_R("0000000 ????? ????? 100 ????? 01100 11", xor    , R(rd) = src1 ^ src2); 

  INSTPAT("??????? ????? ????? ??? ????? 11011 11", jal    , J,
		  R(rd) = s->pc+4; s->dnpc=s->pc+imm);
// setting the least-significant bit of the result to zero (see JALR p47)
  INSTPAT_I("??????? ????? ????? 000 ????? 11001 11", jalr   , 
		  R(rd) = s->pc+4; s->dnpc=(src1+imm)&(~1));


	INSTPAT_B_IMM("000",beq	,src1==src2);
	INSTPAT_B_IMM("001",bneq,src1!=src2);
	INSTPAT_B_IMM("100",blt	,(sword_t)src1<(sword_t)src2);
	INSTPAT_B_IMM("101",bge	,(sword_t)src1>=(sword_t)src2);
	INSTPAT_B_IMM("110",bltu,src1<src2);
	INSTPAT_B_IMM("111",bgeu,src1>=src2);


	INSTPAT("0000000 00001 00000 000 00000 11100 11", ebreak , N, NEMUTRAP(s->pc, R(10))); // R(10) is $a0
  INSTPAT("??????? ????? ????? ??? ????? ????? ??", inv    , N, INV(s->pc));
  INSTPAT_END();

  R(0) = 0; // reset $zero to 0

  return 0;
}

int isa_exec_once(Decode *s) {
  s->isa.inst = inst_fetch(&s->snpc, 4);
  return decode_exec(s);
}

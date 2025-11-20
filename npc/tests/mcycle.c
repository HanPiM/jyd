#include <stdio.h>
#include <stdint.h>

void print(){
	uint32_t c1,c2,c3;
	asm volatile ("csrr %0, mcycle" : "=r"(c1));
	asm volatile ("csrr %0, mcycle" : "=r"(c2));
	asm volatile ("csrr %0, mcycle" : "=r"(c3));
	printf(" mcycle: %u %u %u\n",c1,c2,c3);
}

int main(){
	for(int i=0;i<10;i++)
		print();
	
	return 0;
}

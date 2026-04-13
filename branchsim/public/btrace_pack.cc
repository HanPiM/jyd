#include <stdint.h>
#include <assert.h>
#include <stdio.h>

#include "btrace_pack.h"

struct btrace_pack_imp {
	bool is_create = false;
	FILE* fp = nullptr;
	bool use_pclose = false;
	uint32_t count = 0;
	btrace_record_t cur = {0, 0, 0};
};

// in file
// first 4 bytes: count
// then count records, each record is 12 bytes (pc, code, nxt_pc)

#define AssertWr(fp,item) do {\
	assert(fwrite(item, sizeof(*item), 1, fp) == 1);\
} while(0)
#define AssertRd(fp,item) do {\
	assert(fread(item, sizeof(*item), 1, fp) == 1);\
} while(0)

static btrace_pack_t btrace_pack_open_impl(FILE* fp, bool use_pclose) {
	if (fp == nullptr) {
		return nullptr;
	}
	btrace_pack_t pack = new btrace_pack_imp;
	pack->fp = fp;
	pack->use_pclose = use_pclose;
	if (fread(&pack->count, sizeof(pack->count), 1, pack->fp) != 1) {
		if (pack->use_pclose) {
			pclose(pack->fp);
		} else {
			fclose(pack->fp);
		}
		delete pack;
		return nullptr;
	}
	return pack;
}

btrace_pack_t btrace_pack_create(const char* path) {
	btrace_pack_t pack = new btrace_pack_imp;
	pack->fp = fopen(path, "wb");
	assert(pack->fp);
	AssertWr(pack->fp, &pack->count);
	pack->is_create = true;
	return pack;
}

btrace_pack_t btrace_pack_open(const char* path) {
	return btrace_pack_open_impl(fopen(path, "rb"), false);
}

btrace_pack_t btrace_pack_open_fp(FILE* fp, int use_pclose) {
	return btrace_pack_open_impl(fp, use_pclose != 0);
}

void btrace_pack_add(btrace_pack_t pack, const btrace_record_t* record) {
	AssertWr(pack->fp, record);
	pack->count++;
}

int btrace_pack_pick(btrace_pack_t pack, btrace_record_t* record) {
	if(pack->count == 0) return 0;
	AssertRd(pack->fp, record);
	pack->count--;
	return 1;
}

void btrace_pack_close(btrace_pack_t pack) {
	assert(pack->fp);
	if (pack->is_create) {
		fseek(pack->fp, 0, SEEK_SET);
		AssertWr(pack->fp, &pack->count);
	}
	if (pack->use_pclose) {
		pclose(pack->fp);
	} else {
		fclose(pack->fp);
	}
	delete pack;
}


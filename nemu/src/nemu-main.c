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

#include <common.h>
#include "monitor/sdb/sdb.h"

void init_monitor(int, char *[]);
void am_init_monitor();
void engine_start();
int is_exit_status_bad();

int main(int argc, char *argv[]) {
  /* Initialize the monitor. */
#ifdef CONFIG_TARGET_AM
  am_init_monitor();
#else
  init_monitor(argc, argv);
#endif

  /* Start engine. */
  //engine_start();

  // read ./tools/gen-expr/input

  FILE *fp = fopen("./tools/gen-expr/input2", "r");
  if (fp == NULL) {
	perror("Failed to open input file");
	return 1;
  }
  char buffer[65536];

  // line 1 expected result
  // line 2 expression
  // line 3 expected result
  // line 4 expression
  // ...
  while (fgets(buffer, sizeof(buffer), fp) != NULL) {
	char *endptr;
	long expected = strtol(buffer, &endptr, 10);
	if (endptr == buffer || (*endptr != '\n' && *endptr != '\0')) {
	  fprintf(stderr, "Invalid number: %s", buffer);
	  assert(0);
	}
	if (fgets(buffer, sizeof(buffer), fp) == NULL) {
	  fprintf(stderr, "Missing expression after expected result: %ld\n", expected);
	  assert(0);
	}
	bool success = true;
	long result = expr(buffer, &success);
	if (!success) {
		//fprintf(stderr, "Failed to evaluate expression: %s", buffer);
		continue;
	}
	if (result != expected) {
		fprintf(stderr, "Expression: %sExpected: %ld, Got: %ld\n", buffer, expected, result);
		continue;
	}
  }

  return is_exit_status_bad();
}

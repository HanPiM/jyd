#include <am.h>
#include <klib.h>
#include <stdint.h>

typedef struct Node {
  struct Node *next;
  uint32_t value;
} Node;

static Node nodes[32] __attribute__((aligned(4)));
static uint8_t conflict_storage[8192 + sizeof(Node)] __attribute__((aligned(4096)));

static void fail(unsigned kind, unsigned length, unsigned index) {
  printf("xlistrev mismatch kind=%u length=%u index=%u\n", kind, length,
         index);
  halt(1);
}

static inline Node *xlistrev(Node *head) {
  if (head == NULL)
    return NULL;
  asm volatile(".insn r 0x0b, 6, 0, %0, %0, x0\n\t"
               ".insn r 0x0b, 6, 2, %0, %0, x0"
               : "+r"(head)
               :
               : "memory");
  return head;
}

static void verify_order(Node *head, unsigned length, bool reversed) {
  for (unsigned position = 0; position < length; position++) {
    unsigned expected = reversed ? length - position - 1 : position;
    if (head != &nodes[expected])
      fail(reversed ? 0 : 1, length, position);
    head = head->next;
  }
  if (head != NULL)
    fail(2, length, length);
}

static void run_length(unsigned length) {
  for (unsigned index = 0; index < length; index++) {
    nodes[index].next = index + 1 < length ? &nodes[index + 1] : NULL;
    nodes[index].value = index ^ 0x5a5a5a5au;
  }

  Node *head = xlistrev(length == 0 ? NULL : &nodes[0]);
  verify_order(head, length, true);
  head = xlistrev(head);
  verify_order(head, length, false);
}

static void run_index_conflict(void) {
  Node *first = (Node *)(conflict_storage + 0);
  Node *second = (Node *)(conflict_storage + 4096);
  Node *third = (Node *)(conflict_storage + 8192);
  first->next = second;
  second->next = third;
  third->next = NULL;

  Node *head = xlistrev(first);
  if (head != third || third->next != second || second->next != first ||
      first->next != NULL)
    fail(3, 3, 0);
  head = xlistrev(head);
  if (head != first || first->next != second || second->next != third ||
      third->next != NULL)
    fail(4, 3, 0);
}

int main(void) {
  static const unsigned lengths[] = {0, 1, 2, 3, 7, 15, 31};
  for (unsigned test = 0; test < sizeof(lengths) / sizeof(lengths[0]); test++)
    run_length(lengths[test]);
  run_index_conflict();
  printf("xlistrev-directed: PASS\n");
  return 0;
}

#include "../../main/cpp/generation_budget.h"
#include <cassert>
#include <climits>
int main() {
    assert(generation_budget(1, 512) == 8);
    assert(generation_budget(10, 512) == 14);
    assert(generation_budget(100, 512) == 131);
    assert(generation_budget(1000, 512) == 512);
    assert(generation_budget(INT_MAX, 1024) == 1024);
}

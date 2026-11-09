#include <cassert>
#include "../../main/cpp/prompt_budget.h"

int main() {
    assert(prompt_fits(512, 512, 2048, 256));
    assert(!prompt_fits(513, 512, 2048, 256));
    assert(!prompt_fits(300, 512, 512, 256));
    assert(!prompt_fits(1, 512, 256, 256));
    assert(!prompt_fits(100, 64, 2048, 256));

    // The shipped page-level batch numbers (#149): n_batch equals n_ctx, so the context ceiling
    // binds alone and a page prompt may reach 2048 - 768 - 8 tokens.
    assert(prompt_fits(1272, 2048, 2048, 768));
    assert(!prompt_fits(1273, 2048, 2048, 768));
}

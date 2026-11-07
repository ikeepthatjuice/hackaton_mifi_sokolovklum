#pragma once
#include <algorithm>

// Real input packet tokens only, excluding fixed/chat prompt; one token for EOS.
inline int generation_budget(int input_tokens, int ceiling) {
    const auto scaled = (static_cast<long long>(input_tokens) * 13 + 9) / 10 + 1;
    return static_cast<int>(std::min<long long>(ceiling, std::max<long long>(8, scaled)));
}

#include "../../main/cpp/prompt.h"
#include <cassert>
#include <string>
int occurrences(const std::string & text, const std::string & needle) {
    int count = 0;
    for (size_t at = 0; (at = text.find(needle, at)) != std::string::npos; at += needle.size()) ++count;
    return count;
}
int main() {
    for (bool batch : {false, true}) {
        const auto prefix = transcript_prompt::prefix(batch);
        assert(occurrences(prefix, "<|im_start|>assistant") == 1); // one few-shot
        assert(prefix.find("/no_think") != std::string::npos);
        const std::string tail = "<|im_start|>user\n";
        assert(prefix.size() >= tail.size() && prefix.substr(prefix.size() - tail.size()) == tail);
        assert(transcript_prompt::suffix().find("/no_think") != std::string::npos);
        assert(transcript_prompt::suffix().find("<think>\n\n</think>") != std::string::npos);
    }
    assert(transcript_prompt::prefix(false) != transcript_prompt::prefix(true));
}

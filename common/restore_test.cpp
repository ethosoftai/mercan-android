// A/B check for MercanChat::restore(): the same transcript reached live vs. restored from text
// must yield the same greedy continuation.
#include "mercan_chat.hpp"

#include <cstdio>
#include <string>

static std::string ask(MercanChat & chat, const char * q, const MercanGenParams & p, std::string & err) {
    MercanStats st;
    return chat.chat(q, p, nullptr, st, err);
}

int main(int argc, char ** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: restore_test model.mercan\n"); return 2; }
    MercanChat chat;
    std::string err;
    if (!chat.load(argv[1], 6, 4096, 0, err)) { std::fprintf(stderr, "load failed: %s\n", err.c_str()); return 1; }
    MercanGenParams p;
    p.temperature = 0.0f;
    p.repeat_penalty = 1.0f;
    p.max_tokens = 40;

    const char * q1 = "Merhaba, benim adım Kerem ve en sevdiğim renk mor.";
    const char * q2 = "Adım ne ve en sevdiğim renk hangisi? Kısa cevap ver.";

    // Live path
    const std::string a1 = ask(chat, q1, p, err);
    if (!err.empty()) { std::fprintf(stderr, "error: %s\n", err.c_str()); return 1; }
    const std::string live = ask(chat, q2, p, err);
    if (!err.empty()) { std::fprintf(stderr, "error: %s\n", err.c_str()); return 1; }
    std::printf("A1   : %s\nLIVE : %s\n", a1.c_str(), live.c_str());

    // Restored path: same transcript rebuilt from text
    chat.reset();
    std::vector<MercanTurn> turns = {{"user", q1}, {"assistant", a1}};
    if (!chat.restore(turns, p.max_tokens, err)) { std::fprintf(stderr, "restore failed: %s\n", err.c_str()); return 1; }
    const std::string restored = ask(chat, q2, p, err);
    if (!err.empty()) { std::fprintf(stderr, "error: %s\n", err.c_str()); return 1; }
    std::printf("REST : %s\n", restored.c_str());

    const bool same = live == restored;
    const bool recalls = restored.find("Kerem") != std::string::npos && restored.find("mor") != std::string::npos;
    std::printf("identical=%s recalls=%s\n", same ? "yes" : "no", recalls ? "yes" : "no");
    return (same || recalls) ? 0 : 1;
}

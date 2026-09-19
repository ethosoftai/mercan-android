// Headless smoke test for the shared engine: loads a model, runs two chat turns
// (exercises incremental KV reuse) and prints throughput. Built for Linux/WSL only.
#include "mercan_chat.hpp"

#include <cstdio>
#include <string>

int main(int argc, char ** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: chat_test model.mercan [threads]\n"); return 2; }
    const int threads = argc > 2 ? std::atoi(argv[2]) : 0;

    MercanChat chat;
    std::string err;
    if (!chat.load(argv[1], threads, 4096, 0, err)) { std::fprintf(stderr, "load failed: %s\n", err.c_str()); return 1; }
    std::printf("loaded; threads=%d ctx=%u\n", chat.threads(), chat.context_size());

    MercanGenParams p;
    p.temperature = 0.0f; // deterministic for the test
    p.max_tokens = 96;
    const char * prompts[] = {
        "Merhaba! Türkiye'nin başkenti neresidir? Kısaca cevapla.",
        "Peki o şehrin nüfusu yaklaşık ne kadar?",
    };
    for (const char * q : prompts) {
        MercanStats st;
        std::printf("\n> %s\n", q);
        std::fflush(stdout);
        const std::string reply = chat.chat(q, p, [](const std::string & piece) {
            std::fputs(piece.c_str(), stdout); std::fflush(stdout); return true; }, st, err);
        if (!err.empty()) { std::fprintf(stderr, "\nerror: %s\n", err.c_str()); return 1; }
        std::printf("\n[prompt %d tok in %.0f ms | gen %d tok @ %.1f tok/s | ctx %d/%u]\n",
            st.prompt_tokens, st.prompt_ms, st.gen_tokens, st.tokens_per_sec(), chat.tokens_in_context(), chat.context_size());
        if (reply.empty()) { std::fprintf(stderr, "empty reply\n"); return 1; }
    }
    // Restore the same transcript from scratch (what the Android app does when reopening a chat)
    // and ask a question that only makes sense with that context.
    std::vector<MercanTurn> turns;
    turns.push_back({"user", prompts[0]});
    turns.push_back({"assistant", "Ankara, Türkiye'nin başkentidir."});
    turns.push_back({"user", prompts[1]});
    turns.push_back({"assistant", "Ankara'nın nüfusu yaklaşık 5,8 milyondur."});
    chat.reset();
    if (!chat.restore(turns, p.max_tokens, err)) { std::fprintf(stderr, "restore failed: %s\n", err.c_str()); return 1; }
    std::printf("\nrestored %d tokens of history\n", chat.tokens_in_context());
    {
        MercanStats st;
        const char * q = "Az önce hangi şehirden bahsettik? Tek kelimeyle cevapla.";
        std::printf("\n> %s\n", q);
        const std::string reply = chat.chat(q, p, [](const std::string & piece) {
            std::fputs(piece.c_str(), stdout); std::fflush(stdout); return true; }, st, err);
        if (!err.empty()) { std::fprintf(stderr, "\nerror: %s\n", err.c_str()); return 1; }
        std::printf("\n[prompt %d tok | gen %d tok @ %.1f tok/s | ctx %d/%u]\n",
            st.prompt_tokens, st.gen_tokens, st.tokens_per_sec(), chat.tokens_in_context(), chat.context_size());
        if (reply.find("Ankara") == std::string::npos) { std::fprintf(stderr, "restore context check FAILED\n"); return 1; }
    }
    chat.reset();
    std::printf("\nOK\n");
    return 0;
}

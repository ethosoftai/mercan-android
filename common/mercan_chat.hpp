// Shared chat engine on top of the libmercan C ABI.
// Used by the Windows GUI and the Android JNI bridge.
#pragma once

#include "mercan.h"

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <random>
#include <string>
#include <vector>

struct MercanGenParams {
    int   max_tokens     = 512;
    float temperature    = 0.7f;
    int   top_k          = 40;
    float top_p          = 0.95f;
    float repeat_penalty = 1.10f;
    int   repeat_last_n  = 64;
    // Text placed at the start of the assistant turn (decoded as prompt, not generated),
    // e.g. "Düşünce:\n" to force a reasoning section. Not echoed through on_piece.
    std::string assistant_prefix;
    // Extra text-level stop strings (on top of the built-in end markers). Never emitted.
    std::vector<std::string> stop;
};

struct MercanTurn {
    std::string role;     // "user" or "assistant"
    std::string content;
};

struct MercanStats {
    int    prompt_tokens = 0;
    int    gen_tokens    = 0;
    double prompt_ms     = 0.0;
    double gen_ms        = 0.0;
    double tokens_per_sec() const { return gen_ms > 0.0 ? gen_tokens * 1000.0 / gen_ms : 0.0; }
};

class MercanChat {
public:
    MercanChat() = default;
    ~MercanChat();

    MercanChat(const MercanChat &) = delete;
    MercanChat & operator=(const MercanChat &) = delete;

    // Loads model + creates a reusable context. n_threads <= 0 -> auto.
    // use_mmap=false reads the weights into RAM so the file can be deleted right after loading
    // (Windows cannot unlink a mapped file).
    bool load(const std::string & model_path, int n_threads, int n_ctx, int n_gpu_layers, std::string & error, bool use_mmap = true);
    void unload();
    bool loaded() const { return model_ != nullptr && ctx_ != nullptr; }

    // Runs one chat turn. on_piece receives UTF-8 safe text fragments; return false to cancel.
    // Returns the full assistant reply (also delivered via on_piece).
    std::string chat(const std::string & user_message,
                     const MercanGenParams & params,
                     const std::function<bool(const std::string &)> & on_piece,
                     MercanStats & stats,
                     std::string & error);

    // Forget the conversation (KV cache cleared).
    void reset();

    // System turn placed at the start of every conversation (empty = none). Takes effect on
    // the next reset()/restore(); the caller re-prefills the running conversation.
    void set_system_prompt(const std::string & prompt) { system_prompt_ = prompt; }
    const std::string & system_prompt() const { return system_prompt_; }

    // Replace the conversation with `turns` (prefilled into the KV cache so the next chat()
    // continues it). Oldest turns are dropped when the transcript exceeds the context window.
    bool restore(const std::vector<MercanTurn> & turns, int reserve_tokens, std::string & error);
    void cancel() { cancel_.store(true); }

    int  threads() const { return n_threads_; }
    void set_threads(int n) { n_threads_ = n; }
    uint32_t context_size() const { return n_ctx_; }
    int  tokens_in_context() const { return static_cast<int>(history_.size()); }
    std::string model_path() const { return model_path_; }

    static int default_thread_count();

    // Name of the ggml CPU backend variant (e.g. "zen4", "android_armv8.6_1") that scores best
    // on this machine, found by scanning `dir` for ggml-cpu-* dynamic libraries. Empty if none.
    static std::string best_cpu_variant(const std::string & dir);

private:
    std::vector<mercan_token> tokenize(const std::string & text);
    std::string piece(mercan_token t, bool special);
    bool decode(const mercan_token * toks, int n, std::string & error);
    mercan_token sample(const float * logits, int n_vocab, const MercanGenParams & p);
    bool recreate_context(std::string & error);

    mercan_model *   model_ = nullptr;
    mercan_context * ctx_   = nullptr;
    std::string      model_path_;
    std::string      system_prompt_;
    int              n_threads_ = 0;
    uint32_t         n_ctx_ = 4096;
    int              n_gpu_layers_ = 0;
    std::vector<mercan_token> history_;   // tokens currently in the KV cache
    bool             first_turn_ = true;
    std::mt19937     rng_{std::random_device{}()};
    std::atomic<bool> cancel_{false};
    std::mutex       mu_;
};

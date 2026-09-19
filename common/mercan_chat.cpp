#include "mercan_chat.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>
#include <unordered_map>

#include <filesystem>

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#else
#  include <dlfcn.h>
#endif

std::string MercanChat::best_cpu_variant(const std::string & dir) {
    namespace fs = std::filesystem;
#if defined(_WIN32)
    const std::string prefix = "ggml-cpu-", ext = ".dll";
#else
    const std::string prefix = "libggml-cpu-", ext = ".so";
#endif
    std::string best;
    int best_score = 0;
    std::error_code ec;
    for (const auto & entry : fs::directory_iterator(dir, ec)) {
        const std::string name = entry.path().filename().string();
        if (name.compare(0, prefix.size(), prefix) != 0 || name.size() <= prefix.size() + ext.size() ||
            name.compare(name.size() - ext.size(), ext.size(), ext) != 0) continue;
        const std::string variant = name.substr(prefix.size(), name.size() - prefix.size() - ext.size());
        typedef int (*score_fn)(void);
        score_fn score = nullptr;
#if defined(_WIN32)
        HMODULE h = LoadLibraryW(entry.path().wstring().c_str());
        if (!h) continue;
        score = reinterpret_cast<score_fn>(GetProcAddress(h, "ggml_backend_score"));
        const int s = score ? score() : 0;
        FreeLibrary(h);
#else
        void * h = dlopen(entry.path().string().c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!h) continue;
        score = reinterpret_cast<score_fn>(dlsym(h, "ggml_backend_score"));
        const int s = score ? score() : 0;
        dlclose(h);
#endif
        if (s > best_score) { best_score = s; best = variant; }
    }
    return best;
}

static const char * kImStart = "<|im_start|>";
static const char * kImEnd   = "<|im_end|>";
// Text-level terminators: the model occasionally spells its end-of-sequence marker out as
// plain tokens instead of emitting the EOS id, then keeps rambling.
static const char * const kStopStrings[] = { "<|im_end|>", "</s>", "<|endoftext|>", "<|im_start|>", "</solution>" };

static double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// Length of the longest prefix of s that is valid, complete UTF-8.
static size_t utf8_complete_prefix(const std::string & s) {
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len = 1;
        if      ((c & 0x80) == 0x00) len = 1;
        else if ((c & 0xE0) == 0xC0) len = 2;
        else if ((c & 0xF0) == 0xE0) len = 3;
        else if ((c & 0xF8) == 0xF0) len = 4;
        else { ++i; continue; } // stray continuation byte: pass through
        if (i + len > s.size()) return i; // incomplete sequence at the tail
        i += len;
    }
    return i;
}

// Longest suffix of s that is a proper prefix of `pat` (for stop-string holding).
static size_t suffix_prefix_overlap(const std::string & s, const char * pat) {
    const size_t plen = std::strlen(pat);
    const size_t max = std::min(s.size(), plen - 1);
    for (size_t k = max; k > 0; --k) {
        if (s.compare(s.size() - k, k, pat, k) == 0) return k;
    }
    return 0;
}

static size_t stop_hold(const std::string & s, const std::vector<std::string> & extra) {
    size_t hold = 0;
    for (const char * pat : kStopStrings) hold = std::max(hold, suffix_prefix_overlap(s, pat));
    for (const auto & pat : extra) if (!pat.empty()) hold = std::max(hold, suffix_prefix_overlap(s, pat.c_str()));
    return hold;
}

// Position of the earliest stop string in s, or npos.
static size_t find_stop(const std::string & s, const std::vector<std::string> & extra) {
    size_t best = std::string::npos;
    for (const char * pat : kStopStrings) {
        const size_t pos = s.find(pat);
        if (pos != std::string::npos && pos < best) best = pos;
    }
    for (const auto & pat : extra) {
        if (pat.empty()) continue;
        const size_t pos = s.find(pat);
        if (pos != std::string::npos && pos < best) best = pos;
    }
    return best;
}

static std::string system_turn(const std::string & prompt) {
    if (prompt.empty()) return {};
    return std::string(kImStart) + "system\n" + prompt + kImEnd + "\n";
}

int MercanChat::default_thread_count() {
#if defined(_WIN32)
    // Prefer physical cores: hyperthreads do not help GGML matmul.
    DWORD len = 0;
    GetLogicalProcessorInformation(nullptr, &len);
    if (len > 0) {
        std::vector<SYSTEM_LOGICAL_PROCESSOR_INFORMATION> info(len / sizeof(SYSTEM_LOGICAL_PROCESSOR_INFORMATION) + 1);
        if (GetLogicalProcessorInformation(info.data(), &len)) {
            int cores = 0;
            const size_t n = len / sizeof(SYSTEM_LOGICAL_PROCESSOR_INFORMATION);
            for (size_t i = 0; i < n; ++i) if (info[i].Relationship == RelationProcessorCore) ++cores;
            if (cores > 0) return cores;
        }
    }
    return std::max(1u, std::thread::hardware_concurrency() / 2);
#elif defined(__ANDROID__)
    const unsigned hw = std::max(1u, std::thread::hardware_concurrency());
    if (hw >= 8) return 4;          // typical big.LITTLE: use the performance cluster
    return static_cast<int>(std::max(1u, hw / 2));
#else
    return static_cast<int>(std::max(1u, std::thread::hardware_concurrency() / 2));
#endif
}

MercanChat::~MercanChat() { unload(); }

bool MercanChat::load(const std::string & model_path, int n_threads, int n_ctx, int n_gpu_layers, std::string & error, bool use_mmap) {
    std::lock_guard<std::mutex> lock(mu_);
    unload();
    mercan_backend_init();
    mercan_model_params mp = mercan_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.use_mmap = use_mmap;
    model_ = mercan_model_load(model_path.c_str(), mp);
    if (!model_) {
        error = std::string("model load failed: ") + mercan_last_error();
        return false;
    }
    model_path_ = model_path;
    n_threads_ = n_threads > 0 ? n_threads : default_thread_count();
    n_ctx_ = n_ctx > 0 ? static_cast<uint32_t>(n_ctx) : 4096u;
    n_gpu_layers_ = n_gpu_layers;
    if (!recreate_context(error)) {
        mercan_model_free(model_);
        model_ = nullptr;
        return false;
    }
    return true;
}

bool MercanChat::recreate_context(std::string & error) {
    if (ctx_) { mercan_context_free(ctx_); ctx_ = nullptr; }
    mercan_context_params cp = mercan_context_default_params();
    cp.n_ctx = n_ctx_;
    cp.n_batch = 512;
    cp.n_threads = cp.n_threads_batch = n_threads_;
    ctx_ = mercan_context_create(model_, cp);
    if (!ctx_) {
        error = std::string("context creation failed: ") + mercan_last_error();
        return false;
    }
    history_.clear();
    first_turn_ = true;
    return true;
}

void MercanChat::unload() {
    if (ctx_)   { mercan_context_free(ctx_); ctx_ = nullptr; }
    if (model_) { mercan_model_free(model_); model_ = nullptr; }
    history_.clear();
    first_turn_ = true;
}

void MercanChat::reset() {
    std::lock_guard<std::mutex> lock(mu_);
    if (ctx_) mercan_context_clear(ctx_);
    history_.clear();
    first_turn_ = true;
}

bool MercanChat::restore(const std::vector<MercanTurn> & turns, int reserve_tokens, std::string & error) {
    std::lock_guard<std::mutex> lock(mu_);
    cancel_.store(false);
    if (!loaded()) { error = "model not loaded"; return false; }
    if (ctx_) mercan_context_clear(ctx_);
    history_.clear();
    first_turn_ = true;

    // Every restored turn is complete (<|im_end|>\n decoded), so the next chat() must not
    // prepend the dangling <|im_end|> -> keep first_turn_ == true.
    const std::vector<mercan_token> sys_toks = tokenize(system_turn(system_prompt_));
    const int budget = static_cast<int>(n_ctx_) - std::max(0, reserve_tokens) - 8 - static_cast<int>(sys_toks.size());
    std::vector<std::vector<mercan_token>> per_turn;
    per_turn.reserve(turns.size());
    for (const auto & t : turns) {
        const std::string text = std::string(kImStart) + t.role + "\n" + t.content + kImEnd + "\n";
        per_turn.push_back(tokenize(text));
    }
    // Drop oldest turns until the transcript fits.
    size_t start = 0;
    int total = 0;
    for (const auto & v : per_turn) total += static_cast<int>(v.size());
    while (start < per_turn.size() && total > budget) {
        total -= static_cast<int>(per_turn[start].size());
        ++start;
    }
    // Never start the window on an assistant turn.
    while (start < per_turn.size() && turns[start].role != "user") {
        total -= static_cast<int>(per_turn[start].size());
        ++start;
    }
    std::vector<mercan_token> toks = sys_toks;
    toks.reserve(static_cast<size_t>(std::max(0, total)) + sys_toks.size());
    for (size_t i = start; i < per_turn.size(); ++i) toks.insert(toks.end(), per_turn[i].begin(), per_turn[i].end());
    if (toks.empty()) return true;
    if (!decode(toks.data(), static_cast<int>(toks.size()), error)) {
        mercan_context_clear(ctx_);
        history_.clear();
        return false;
    }
    return true;
}

std::vector<mercan_token> MercanChat::tokenize(const std::string & text) {
    std::vector<mercan_token> out;
    if (text.empty()) return out;
    int32_t n = mercan_tokenize(model_, text.data(), text.size(), false, false, nullptr, 0);
    if (n < 0) n = -n;
    out.resize(static_cast<size_t>(std::max(1, n)));
    int32_t got = mercan_tokenize(model_, text.data(), text.size(), false, false, out.data(), static_cast<int32_t>(out.size()));
    if (got < 0) {
        out.resize(static_cast<size_t>(-got));
        got = mercan_tokenize(model_, text.data(), text.size(), false, false, out.data(), static_cast<int32_t>(out.size()));
    }
    if (got < 0) got = 0;
    out.resize(static_cast<size_t>(got));
    return out;
}

std::string MercanChat::piece(mercan_token t, bool special) {
    char small[128];
    int32_t n = mercan_token_to_piece(model_, t, small, sizeof(small), special);
    if (n >= 0 && n <= static_cast<int32_t>(sizeof(small))) return std::string(small, small + n);
    if (n < 0) {
        std::vector<char> buf(static_cast<size_t>(-n));
        n = mercan_token_to_piece(model_, t, buf.data(), static_cast<int32_t>(buf.size()), special);
        if (n >= 0) return std::string(buf.data(), buf.data() + n);
    }
    return {};
}

bool MercanChat::decode(const mercan_token * toks, int n, std::string & error) {
    const int batch = 512;
    for (int i = 0; i < n; i += batch) {
        if (cancel_.load()) { error = "cancelled"; return false; }
        const int len = std::min(batch, n - i);
        if (mercan_decode(ctx_, toks + i, len) != 0) {
            error = std::string("decode failed: ") + mercan_last_error();
            return false;
        }
        history_.insert(history_.end(), toks + i, toks + i + len);
    }
    return true;
}

mercan_token MercanChat::sample(const float * logits_in, int n_vocab, const MercanGenParams & p) {
    std::vector<float> logits(logits_in, logits_in + n_vocab);

    // Repetition penalty over the recent window (skips nothing: the window only holds
    // conversation tokens, which is what we want to discourage repeating).
    if (p.repeat_penalty > 1.0f && p.repeat_last_n > 0 && !history_.empty()) {
        const size_t start = history_.size() > static_cast<size_t>(p.repeat_last_n)
            ? history_.size() - static_cast<size_t>(p.repeat_last_n) : 0;
        for (size_t i = start; i < history_.size(); ++i) {
            const mercan_token t = history_[i];
            if (t < 0 || t >= n_vocab) continue;
            float & l = logits[static_cast<size_t>(t)];
            l = l > 0.0f ? l / p.repeat_penalty : l * p.repeat_penalty;
        }
    }

    if (p.temperature <= 0.0f) {
        return static_cast<mercan_token>(std::max_element(logits.begin(), logits.end()) - logits.begin());
    }

    const int top_k = std::max(1, std::min(p.top_k > 0 ? p.top_k : n_vocab, n_vocab));
    std::vector<std::pair<float, int32_t>> ranked;
    ranked.reserve(static_cast<size_t>(n_vocab));
    for (int32_t i = 0; i < n_vocab; ++i) ranked.emplace_back(logits[static_cast<size_t>(i)], i);
    std::partial_sort(ranked.begin(), ranked.begin() + top_k, ranked.end(),
        [](const auto & a, const auto & b) { return a.first > b.first; });
    ranked.resize(static_cast<size_t>(top_k));

    const float max_logit = ranked.front().first;
    std::vector<double> weights;
    weights.reserve(ranked.size());
    double total = 0.0;
    for (const auto & x : ranked) {
        const double w = std::exp((static_cast<double>(x.first) - max_logit) / p.temperature);
        weights.push_back(w);
        total += w;
    }
    if (p.top_p > 0.0f && p.top_p < 1.0f && total > 0.0) {
        double cumulative = 0.0;
        size_t keep = 0;
        for (; keep < weights.size(); ++keep) {
            cumulative += weights[keep] / total;
            if (cumulative >= p.top_p) { ++keep; break; }
        }
        keep = std::max<size_t>(1, std::min(keep, weights.size()));
        ranked.resize(keep);
        weights.resize(keep);
    }
    std::discrete_distribution<size_t> dist(weights.begin(), weights.end());
    return static_cast<mercan_token>(ranked[dist(rng_)].second);
}

std::string MercanChat::chat(const std::string & user_message,
                             const MercanGenParams & params,
                             const std::function<bool(const std::string &)> & on_piece,
                             MercanStats & stats,
                             std::string & error) {
    std::lock_guard<std::mutex> lock(mu_);
    stats = MercanStats{};
    cancel_.store(false);
    if (!loaded()) { error = "model not loaded"; return {}; }

    // Chat template: <|im_start|>{role}\n{content}<|im_end|>\n
    // The previous assistant turn's <|im_end|> was sampled but never decoded, so the
    // continuation for every turn after the first starts with it.
    std::string turn;
    if (!first_turn_) turn += std::string(kImEnd) + "\n";
    if (history_.empty()) turn += system_turn(system_prompt_);
    turn += std::string(kImStart) + "user\n" + user_message + kImEnd + "\n" + kImStart + "assistant\n" + params.assistant_prefix;

    std::vector<mercan_token> toks = tokenize(turn);
    if (toks.empty()) { error = "prompt produced no tokens"; return {}; }

    // Keep room for the reply; drop history when the window would overflow.
    const int budget = static_cast<int>(n_ctx_) - params.max_tokens - 8;
    if (static_cast<int>(history_.size() + toks.size()) > budget) {
        mercan_context_clear(ctx_);
        history_.clear();
        first_turn_ = true;
        turn = system_turn(system_prompt_) + kImStart + "user\n" + user_message + kImEnd + "\n" + kImStart + "assistant\n" + params.assistant_prefix;
        toks = tokenize(turn);
        if (static_cast<int>(toks.size()) > budget) {
            error = "message too long for the context window";
            return {};
        }
    }

    const double t0 = now_ms();
    if (!decode(toks.data(), static_cast<int>(toks.size()), error)) return {};
    stats.prompt_tokens = static_cast<int>(toks.size());
    stats.prompt_ms = now_ms() - t0;
    first_turn_ = false;

    const int32_t n_vocab = mercan_vocab_size(model_);
    const mercan_token eos = mercan_eos_token(model_);
    std::string reply;     // everything emitted to the caller
    std::string pending;   // held back: incomplete UTF-8 or a possible stop-string prefix
    const double t1 = now_ms();

    for (int i = 0; i < params.max_tokens; ++i) {
        if (cancel_.load()) break;
        const float * logits = mercan_logits(ctx_);
        if (!logits) { error = "no logits"; break; }
        const mercan_token next = sample(logits, n_vocab, params);
        if (next == eos) break;

        const std::string sp = piece(next, true);
        if (sp.find(kImEnd) != std::string::npos || sp.find(kImStart) != std::string::npos) break;

        pending += piece(next, false);
        // Stop-string detection on the raw text stream as well.
        const size_t stop = find_stop(pending, params.stop);
        if (stop != std::string::npos) { pending.resize(stop); break; }

        // Flush the part of `pending` that is complete UTF-8 and cannot be a stop-string prefix.
        size_t flush = utf8_complete_prefix(pending);
        const size_t hold = stop_hold(pending.substr(0, flush), params.stop);
        flush -= hold;
        if (flush > 0) {
            const std::string out = pending.substr(0, flush);
            pending.erase(0, flush);
            reply += out;
            if (on_piece && !on_piece(out)) { cancel_.store(true); }
        }

        stats.gen_tokens++;
        if (mercan_decode(ctx_, &next, 1) != 0) {
            error = std::string("decode failed: ") + mercan_last_error();
            break;
        }
        history_.push_back(next);
    }
    // Flush whatever remains (complete UTF-8 only).
    const size_t tail = utf8_complete_prefix(pending);
    if (tail > 0) {
        const std::string out = pending.substr(0, tail);
        reply += out;
        if (on_piece) on_piece(out);
    }
    stats.gen_ms = now_ms() - t1;
    return reply;
}

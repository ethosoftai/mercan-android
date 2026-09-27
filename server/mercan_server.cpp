#include "mercan_server.hpp"

#include "httplib.h"
#include <nlohmann/json.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <functional>
#include <random>

using json = nlohmann::ordered_json;

namespace {

// Same thinking protocol as the Android / legacy Windows apps: the user turn gets an
// instruction suffix, the assistant turn is prefilled with "Düşünce:\n" and the model
// separates its final answer with "Cevap:". The stream is rewritten to <think>…</think>
// so Open WebUI renders the reasoning as a collapsible block.
const char * kThinkSuffix  = "\n\n(Yanıtını iki bölümde ver: önce \"Düşünce:\" altında soruyu çözmek için en fazla 3 kısa madde, sonra \"Cevap:\" altında son cevap.)";
const char * kThinkPrefill = "Düşünce:\n";

bool is_space(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

std::string trim(const std::string & s) {
    size_t b = 0, e = s.size();
    while (b < e && is_space(s[b])) ++b;
    while (e > b && is_space(s[e - 1])) --e;
    return s.substr(b, e - b);
}

std::string content_text(const json & c) {
    if (c.is_string()) return c.get<std::string>();
    std::string out;
    if (c.is_array()) {
        for (const auto & part : c) {
            if (part.is_object() && part.value("type", "") == "text" && part.contains("text") && part["text"].is_string()) {
                if (!out.empty()) out += "\n";
                out += part["text"].get<std::string>();
            }
        }
    }
    return out;
}

// Removes reasoning blocks the client may echo back: <details type="reasoning">…</details>
// (Open WebUI's stored form) and raw <think>…</think>.
std::string strip_reasoning(std::string s) {
    for (;;) {
        const size_t a = s.find("<think>");
        if (a == std::string::npos) break;
        const size_t b = s.find("</think>", a);
        s.erase(a, b == std::string::npos ? std::string::npos : b + 8 - a);
    }
    size_t from = 0;
    for (;;) {
        const size_t a = s.find("<details", from);
        if (a == std::string::npos) break;
        const size_t gt = s.find('>', a);
        const size_t b = s.find("</details>", a);
        if (gt == std::string::npos || b == std::string::npos) break;
        if (s.substr(a, gt - a).find("reasoning") != std::string::npos) s.erase(a, b + 10 - a);
        else from = b + 10;
    }
    return s;
}

double num(const json & body, const char * key, double def) {
    auto it = body.find(key);
    return (it != body.end() && it->is_number()) ? it->get<double>() : def;
}

std::string random_id() {
    static const char * hex = "0123456789abcdef";
    std::random_device rd;
    std::string s = "chatcmpl-";
    for (int i = 0; i < 24; ++i) s += hex[rd() & 15];
    return s;
}

long long unix_now() {
    using namespace std::chrono;
    return duration_cast<seconds>(system_clock::now().time_since_epoch()).count();
}

bool ieq(const std::string & s, size_t i, const char * lit) {
    const size_t n = std::strlen(lit);
    if (i + n > s.size()) return false;
    for (size_t k = 0; k < n; ++k) {
        char c = s[i + k];
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
        if (c != lit[k]) return false;
    }
    return true;
}

// Finds the answer marker (`Cevap:` at a line/sentence start, markdown decorations allowed).
// Returns [start, end) of the marker; start == npos when absent.
std::pair<size_t, size_t> find_answer_mark(const std::string & s, size_t from) {
    for (size_t i = from; i < s.size(); ++i) {
        if (!ieq(s, i, "cevap")) continue;
        size_t b = i;
        bool spaced = false;
        while (b > 0 && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '*' || s[b - 1] == '#')) {
            spaced |= s[b - 1] == ' ' || s[b - 1] == '\t';
            --b;
        }
        const bool boundary = b == 0 || s[b - 1] == '\n' || s[b - 1] == '\r' || (s[b - 1] == '.' && spaced);
        if (!boundary) continue;
        size_t j = i + 5;
        while (j < s.size() && (s[j] == ' ' || s[j] == '*')) ++j;
        if (j >= s.size() || s[j] != ':') continue;
        ++j;
        while (j < s.size() && (is_space(s[j]) || s[j] == '*')) ++j;
        return {std::max(b, from), j};
    }
    return {std::string::npos, std::string::npos};
}

// Length of a leading "Düşünce:" label (model sometimes repeats the prefilled one).
size_t think_label_len(const std::string & s) {
    size_t p = 0;
    while (p < s.size() && (is_space(s[p]) || s[p] == '*')) ++p;
    static const char * forms[] = { "Düşünce", "düşünce", "DÜŞÜNCE" };
    bool hit = false;
    for (const char * f : forms) {
        if (s.compare(p, std::strlen(f), f) == 0) { p += std::strlen(f); hit = true; break; }
    }
    if (!hit) return 0;
    while (p < s.size() && (s[p] == ' ' || s[p] == '*')) ++p;
    if (p >= s.size() || s[p] != ':') return 0;
    ++p;
    while (p < s.size() && (s[p] == ' ' || s[p] == '*' || s[p] == '\t')) ++p;
    return p;
}

size_t utf8_floor(const std::string & s, size_t pos, size_t lo) {
    while (pos > lo && pos < s.size() && (static_cast<unsigned char>(s[pos]) & 0xC0) == 0x80) --pos;
    return pos;
}

// Where the answer section runs on into a new section (the model echoing the instruction,
// a second "Cevap:", or a fresh "Düşünce:"); npos if it does not.
size_t answer_runoff(const std::string & s, size_t from) {
    size_t best = std::string::npos;
    const auto mark = find_answer_mark(s, from);
    if (mark.first != std::string::npos) best = mark.first;
    for (const char * pat : { "Yanıtını iki bölüm", "(Yanıtını", "\"Düşünce:\"", "\nDüşünce:", "\n**Düşünce", "Düşünce:\n" }) {
        const size_t p = s.find(pat, from);
        if (p != std::string::npos && p < best) best = p;
    }
    return best;
}

// Rewrites the raw thinking-mode stream into "<think>\n…\n</think>\n\nanswer".
// out_ failures mean the client went away; stopped() also turns true when the answer is complete.
class ThinkRewriter {
public:
    static constexpr size_t kHold = 24;   // bytes held back so markers are never split

    explicit ThinkRewriter(std::function<bool(const std::string &)> out) : out_(std::move(out)) {}

    void begin() { send("<think>\n"); }

    void feed(const std::string & piece) {
        if (stopped()) return;
        raw_ += piece;
        pump(false);
    }

    void finish() {
        if (failed_) return;
        pump(true);
        if (!answering_) {
            // No explicit answer: close the reasoning and repeat it as the answer.
            send("\n</think>\n\n");
            answering_ = true;
            answer_ = trim(thinking_);
            if (!answer_.empty()) send(answer_);
        }
    }

    bool failed() const { return failed_; }
    bool stopped() const { return failed_ || complete_; }
    const std::string & answer() const { return answer_; }

private:
    bool send(const std::string & s) {
        if (!failed_ && !out_(s)) failed_ = true;
        return !failed_;
    }

    void pump(bool final) {
        if (!answering_) {
            if (!label_checked_) {
                if (!final && raw_.size() < kHold) return;
                emitted_ = think_label_len(raw_);
                label_checked_ = true;
            }
            const auto mark = find_answer_mark(raw_, emitted_);
            if (mark.first == std::string::npos) {
                const size_t upto = final ? raw_.size() : utf8_floor(raw_, raw_.size() > kHold ? raw_.size() - kHold : 0, emitted_);
                emit_thinking(upto);
                return;
            }
            emit_thinking(mark.first);
            send("\n</think>\n\n");
            answering_ = true;
            emitted_ = answer_from_ = mark.second;
        }
        // Answer phase: skip leading decoration, stop at a run-on section.
        if (answer_.empty()) {
            while (emitted_ < raw_.size() && (is_space(raw_[emitted_]) || raw_[emitted_] == '*')) ++emitted_;
            answer_from_ = emitted_;
        }
        const size_t runoff = answer_runoff(raw_, answer_from_ + 1);
        size_t upto;
        if (runoff != std::string::npos) {
            upto = runoff;
            while (upto > emitted_ && is_space(raw_[upto - 1])) --upto;
            complete_ = true;
        } else {
            upto = final ? raw_.size() : utf8_floor(raw_, raw_.size() > kHold ? raw_.size() - kHold : 0, emitted_);
        }
        if (upto > emitted_) {
            const std::string chunk = raw_.substr(emitted_, upto - emitted_);
            emitted_ = upto;
            answer_ += chunk;
            send(chunk);
        }
    }

    void emit_thinking(size_t upto) {
        if (upto <= emitted_) return;
        std::string chunk = raw_.substr(emitted_, upto - emitted_);
        emitted_ = upto;
        if (thinking_.empty()) {   // no leading blank lines inside the block
            size_t k = 0;
            while (k < chunk.size() && is_space(chunk[k])) ++k;
            chunk.erase(0, k);
        }
        if (chunk.empty()) return;
        thinking_ += chunk;
        send(chunk);
    }

    std::function<bool(const std::string &)> out_;
    std::string raw_, thinking_, answer_;
    size_t emitted_ = 0, answer_from_ = 0;
    bool label_checked_ = false, answering_ = false, complete_ = false, failed_ = false;
};

// The model sometimes spells its end marker out with a stray space ("< /s", "<|im_end--")
// or keeps going with an invented next turn.
const char * const kDefaultStops[] = { "< /s", "</ s", "<|", "< [", "<[", "< http", "<http",
                                       "\nKullanıcı:", "\nUser:", "\n### Kullanıcı" };

bool same_turns(const std::vector<MercanTurn> & a, const std::vector<MercanTurn> & b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (a[i].role != b[i].role || a[i].content != b[i].content) return false;
    }
    return true;
}

json error_json(const std::string & message, const char * type = "invalid_request_error") {
    return {{"error", {{"message", message}, {"type", type}}}};
}

} // namespace

MercanServer::MercanServer(MercanChat & chat) : chat_(chat) {}

MercanServer::~MercanServer() { stop(); }

void MercanServer::invalidate_cache() {
    std::lock_guard<std::mutex> lock(gen_mu_);
    cache_valid_ = false;
    cache_turns_.clear();
    cache_system_.clear();
}

bool MercanServer::authorized(const httplib::Request & req) const {
    if (opt_.api_key.empty()) return true;
    return req.get_header_value("Authorization") == "Bearer " + opt_.api_key;
}

int MercanServer::start(const Options & opt, std::string & error) {
    opt_ = opt;
    svr_ = std::make_unique<httplib::Server>();
    svr_->set_read_timeout(600, 0);
    svr_->set_write_timeout(600, 0);
    svr_->set_payload_max_length(64 * 1024 * 1024);

    // Linux paketinde grafik arayüz yok: istenirse sunucu kendi sayfasını / adresinden sunar.
    if (!opt_.ui_dir.empty()) svr_->set_mount_point("/", opt_.ui_dir);

    svr_->Get("/health", [this](const httplib::Request &, httplib::Response & res) {
        res.set_content(json({{"status", "ok"}, {"ready", ready_.load()}, {"backend", opt_.backend}}).dump(), "application/json");
    });
    auto models = [this](const httplib::Request & req, httplib::Response & res) { handle_models(req, res); };
    auto chat = [this](const httplib::Request & req, httplib::Response & res) { handle_chat(req, res); };
    svr_->Get("/v1/models", models);
    svr_->Get("/models", models);
    svr_->Post("/v1/chat/completions", chat);
    svr_->Post("/chat/completions", chat);
#if MERCAN_HAS_STT
    stt_.set_model(opt_.stt_model);
    auto transcribe = [this](const httplib::Request & req, httplib::Response & res) { handle_transcribe(req, res); };
    svr_->Post("/v1/audio/transcriptions", transcribe);
    svr_->Post("/audio/transcriptions", transcribe);
#endif

    int port = opt_.port;
    if (port > 0) {
        if (!svr_->bind_to_port(opt_.host, port)) { error = "port " + std::to_string(port) + " kullanılamıyor"; return -1; }
    } else {
        port = svr_->bind_to_any_port(opt_.host);
        if (port <= 0) { error = "boş port bulunamadı"; return -1; }
    }
    thread_ = std::thread([this] { svr_->listen_after_bind(); });
    return port;
}

void MercanServer::stop() {
    if (svr_) svr_->stop();
    chat_.cancel();
    if (thread_.joinable()) thread_.join();
    svr_.reset();
}

void MercanServer::handle_models(const httplib::Request & req, httplib::Response & res) {
    if (!authorized(req)) { res.status = 401; res.set_content(error_json("unauthorized").dump(), "application/json"); return; }
    const long long now = unix_now();
    json data = json::array();
    // Until the on-device model is loaded there is nothing to offer: in "acil durum modu" the
    // interface opens early and should then list only the remote model.
    if (!ready_.load()) { res.set_content(json({{"object", "list"}, {"data", data}}).dump(), "application/json"); return; }
    data.push_back({{"id", model_id()}, {"object", "model"}, {"created", now}, {"owned_by", "mercan"}, {"name", "Mercan"}});
    data.push_back({{"id", think_model_id()}, {"object", "model"}, {"created", now}, {"owned_by", "mercan"}, {"name", "Mercan"}});
    res.set_content(json({{"object", "list"}, {"data", data}}).dump(), "application/json");
}

void MercanServer::handle_transcribe(const httplib::Request & req, httplib::Response & res) {
#if MERCAN_HAS_STT
    if (!authorized(req)) { res.status = 401; res.set_content(error_json("unauthorized").dump(), "application/json"); return; }
    if (!stt_.available()) { res.status = 503; res.set_content(error_json("konuşma tanıma modeli yok", "server_error").dump(), "application/json"); return; }

    httplib::FormData part;
    if (req.form.has_file("file")) part = req.form.get_file("file");
    else if (!req.form.files.empty()) part = req.form.files.begin()->second;
    if (part.content.empty()) { res.status = 400; res.set_content(error_json("ses dosyası yok").dump(), "application/json"); return; }

    std::string language = opt_.stt_language;
    if (req.form.has_field("language")) {
        const std::string requested = req.form.get_field("language");
        if (!requested.empty()) language = requested;
    }

    std::vector<float> pcm;
    std::string err;
    if (!MercanStt::wav_to_pcm(part.content, pcm, err)) {
        res.status = 400;
        res.set_content(error_json(err).dump(), "application/json");
        return;
    }
    const std::string text = stt_.transcribe(pcm, language, err);
    if (!err.empty()) { res.status = 500; res.set_content(error_json(err, "server_error").dump(), "application/json"); return; }
    res.set_content(json({{"text", text}}).dump(), "application/json");
#else
    (void) req;
    res.status = 501;
    res.set_content(error_json("konuşma tanıma bu yapıda derlenmedi", "server_error").dump(), "application/json");
#endif
}

void MercanServer::handle_chat(const httplib::Request & req, httplib::Response & res) {
    if (!authorized(req)) { res.status = 401; res.set_content(error_json("unauthorized").dump(), "application/json"); return; }
    json body = json::parse(req.body, nullptr, false);
    if (body.is_discarded() || !body.is_object()) { res.status = 400; res.set_content(error_json("invalid JSON").dump(), "application/json"); return; }
    if (!ready_.load()) { res.status = 503; res.set_content(error_json("Mercan modeli henüz yüklenmedi", "server_error").dump(), "application/json"); return; }

    const std::string model = body.value("model", std::string(model_id()));
    const bool think = model == think_model_id();
    const bool stream = body.value("stream", false);

    MercanGenParams p;
    double max_tokens = num(body, "max_tokens", num(body, "max_completion_tokens", opt_.default_max_tokens));
    p.max_tokens     = std::max(1, std::min(3072, static_cast<int>(max_tokens)));
    p.temperature    = static_cast<float>(num(body, "temperature", 0.7));
    p.top_p          = static_cast<float>(num(body, "top_p", 0.95));
    p.top_k          = static_cast<int>(num(body, "top_k", 40));
    p.repeat_penalty = static_cast<float>(num(body, "repeat_penalty", 1.10));
    if (!body.contains("repeat_penalty") && num(body, "frequency_penalty", 0.0) > 0.0)
        p.repeat_penalty = static_cast<float>(1.0 + std::min(2.0, num(body, "frequency_penalty", 0.0)) / 2.0);
    for (const char * s : kDefaultStops) p.stop.emplace_back(s);
    if (body.contains("stop")) {
        const json & s = body["stop"];
        if (s.is_string()) p.stop.push_back(s.get<std::string>());
        else if (s.is_array()) for (const auto & x : s) if (x.is_string()) p.stop.push_back(x.get<std::string>());
    }

    // Conversation: system messages -> system prompt; user/assistant -> turns.
    std::string system;
    std::vector<MercanTurn> turns;
    if (body.contains("messages") && body["messages"].is_array()) {
        for (const auto & m : body["messages"]) {
            if (!m.is_object()) continue;
            const std::string role = m.value("role", "");
            std::string text = m.contains("content") ? content_text(m["content"]) : "";
            if (role == "system" || role == "developer") {
                text = trim(text);
                if (!text.empty()) system += (system.empty() ? "" : "\n\n") + text;
            } else if (role == "user") {
                turns.push_back({"user", trim(text)});
            } else if (role == "assistant") {
                turns.push_back({"assistant", trim(strip_reasoning(text))});
            }
        }
    }
    if (turns.empty() || turns.back().role != "user" || turns.back().content.empty()) {
        res.status = 400;
        res.set_content(error_json("the last message must be a non-empty user message").dump(), "application/json");
        return;
    }
    if (system.empty()) system = opt_.default_system;
    const std::string prompt = turns.back().content;
    turns.pop_back();

    const std::string id = random_id();
    const long long created = unix_now();

    // Runs one generation; `out` receives the text the client should see.
    auto run = [this, think, p, system, turns, prompt](const std::function<bool(const std::string &)> & out,
                                                        MercanStats & st, std::string & err) -> std::string {
        std::unique_lock<std::mutex> lock(gen_mu_, std::try_to_lock);
        if (!lock.owns_lock()) { chat_.cancel(); lock.lock(); }   // newest request wins

        const bool reuse = cache_valid_ && cache_system_ == system && same_turns(cache_turns_, turns);
        cache_valid_ = false;
        if (!reuse) {
            chat_.set_system_prompt(system);
            const int reserve = p.max_tokens + static_cast<int>(prompt.size() / 2) + 64;
            if (!chat_.restore(turns, reserve, err)) return {};
        }

        MercanGenParams gp = p;
        std::string visible;
        std::string reply;
        if (think) {
            gp.assistant_prefix = kThinkPrefill;
            ThinkRewriter rw(out);
            rw.begin();
            chat_.chat(prompt + kThinkSuffix, gp, [&](const std::string & piece) {
                rw.feed(piece);
                return !rw.stopped();
            }, st, err);
            rw.finish();
            visible = rw.answer();
        } else {
            bool alive = true;
            chat_.chat(prompt, gp, [&](const std::string & piece) {
                alive = alive && out(piece);
                if (alive) visible += piece;
                return alive;
            }, st, err);
        }
        if (err.empty()) {
            cache_system_ = system;
            cache_turns_ = turns;
            cache_turns_.push_back({"user", prompt});
            cache_turns_.push_back({"assistant", trim(visible)});
            cache_valid_ = true;
        }
        return visible;
    };

    auto usage_json = [](const MercanStats & st) {
        return json{{"prompt_tokens", st.prompt_tokens}, {"completion_tokens", st.gen_tokens},
                    {"total_tokens", st.prompt_tokens + st.gen_tokens},
                    {"response_token/s", std::round(st.tokens_per_sec() * 100.0) / 100.0},
                    {"prompt_token/s", st.prompt_ms > 0 ? std::round(st.prompt_tokens * 1000.0 / st.prompt_ms * 100.0) / 100.0 : 0.0}};
    };

    if (!stream) {
        std::string text;
        MercanStats st; std::string err;
        run([&](const std::string & piece) { text += piece; return true; }, st, err);
        if (!err.empty()) { res.status = 500; res.set_content(error_json(err, "server_error").dump(), "application/json"); return; }
        json r = {{"id", id}, {"object", "chat.completion"}, {"created", created}, {"model", model},
                  {"choices", json::array({{{"index", 0}, {"message", {{"role", "assistant"}, {"content", text}}},
                                            {"finish_reason", st.gen_tokens >= p.max_tokens ? "length" : "stop"}}})},
                  {"usage", usage_json(st)}};
        res.set_content(r.dump(), "application/json");
        return;
    }

    res.set_header("Cache-Control", "no-cache");
    res.set_header("X-Accel-Buffering", "no");
    res.set_chunked_content_provider("text/event-stream",
        [run, usage_json, id, created, model, max = p.max_tokens](size_t, httplib::DataSink & sink) {
            auto send = [&](const json & j) {
                const std::string line = "data: " + j.dump() + "\n\n";
                return sink.is_writable() && sink.write(line.data(), line.size());
            };
            auto chunk = [&](const json & delta, const json & finish) {
                return json{{"id", id}, {"object", "chat.completion.chunk"}, {"created", created}, {"model", model},
                            {"choices", json::array({{{"index", 0}, {"delta", delta}, {"finish_reason", finish}}})}};
            };
            bool alive = send(chunk({{"role", "assistant"}, {"content", ""}}, nullptr));
            MercanStats st; std::string err;
            if (alive) {
                run([&](const std::string & piece) {
                    alive = alive && send(chunk({{"content", piece}}, nullptr));
                    return alive;
                }, st, err);
            }
            if (alive) {
                if (!err.empty()) {
                    send(error_json(err, "server_error"));
                } else {
                    json last = chunk(json::object(), st.gen_tokens >= max ? "length" : "stop");
                    last["usage"] = usage_json(st);
                    send(last);
                }
                const char * done = "data: [DONE]\n\n";
                sink.write(done, std::strlen(done));
            }
            sink.done();
            return true;
        });
}

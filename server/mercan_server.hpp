// OpenAI-compatible HTTP front end for MercanChat (/v1/models, /v1/chat/completions).
// Lets Open WebUI (or any OpenAI client) talk to the on-device model.
#pragma once

#include "mercan_chat.hpp"
#if MERCAN_HAS_STT
#  include "mercan_stt.hpp"
#endif

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace httplib { class Server; struct Request; struct Response; }

class MercanServer {
public:
    struct Options {
        std::string host = "127.0.0.1";
        int         port = 0;             // 0 = pick a free port
        std::string api_key;              // empty = no auth
        int         default_max_tokens = 1024;
        std::string default_system;       // used when the request has no system message
        std::string backend;              // reported in /health (e.g. "zen4")
        std::string stt_model;            // whisper.cpp model; empty = no /v1/audio/transcriptions
        std::string stt_language = "tr";
        std::string ui_dir;               // dolu ise klasördeki arayüz / adresinden sunulur
    };

    explicit MercanServer(MercanChat & chat);
    ~MercanServer();

    // Binds and starts serving on a background thread. Returns the bound port (or -1).
    int  start(const Options & opt, std::string & error);
    void stop();

    // Chat requests are answered with 503 until the model is ready.
    void set_ready(bool ready) { ready_.store(ready); }
    // Forget the KV-cache key (call after the model is reloaded or reset elsewhere).
    void invalidate_cache();

    static const char * model_id()       { return "mercan-0.8b"; }
    static const char * think_model_id() { return "mercan-0.8b-dusunen"; }

private:
    void handle_models(const httplib::Request & req, httplib::Response & res);
    void handle_chat(const httplib::Request & req, httplib::Response & res);
    void handle_transcribe(const httplib::Request & req, httplib::Response & res);
    bool authorized(const httplib::Request & req) const;

    MercanChat & chat_;
    Options opt_;
    std::unique_ptr<httplib::Server> svr_;
    std::thread thread_;
    std::atomic<bool> ready_{false};
#if MERCAN_HAS_STT
    MercanStt stt_;
#endif

    // Generation is single-sequence: one request at a time; a new one preempts the old.
    std::mutex gen_mu_;
    std::atomic<int> waiting_{0};

    // What the KV cache currently holds, in the form the client will send it back
    // (reasoning stripped, original user text). Guarded by gen_mu_.
    bool cache_valid_ = false;
    std::string cache_system_;
    std::vector<MercanTurn> cache_turns_;
};

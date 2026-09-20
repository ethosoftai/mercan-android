// mercan-server: standalone OpenAI-compatible server for a plain (unencrypted) model file.
//   mercan-server --model model.mercan [--host 127.0.0.1] [--port 8765] [--threads N] [--ctx 4096] [--api-key KEY]
#include "mercan_server.hpp"

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>

static std::atomic<bool> g_quit{false};

int main(int argc, char ** argv) {
    std::string model, host = "127.0.0.1", key, system, stt_model;
    int port = 8765, threads = 0, ctx = 4096;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        if (a == "--model" || a == "-m") model = next();
        else if (a == "--host") host = next();
        else if (a == "--port") port = std::atoi(next().c_str());
        else if (a == "--threads" || a == "-t") threads = std::atoi(next().c_str());
        else if (a == "--ctx" || a == "-c") ctx = std::atoi(next().c_str());
        else if (a == "--api-key") key = next();
        else if (a == "--system") system = next();
        else if (a == "--stt-model") stt_model = next();
        else { std::fprintf(stderr, "unknown argument: %s\n", a.c_str()); return 2; }
    }
    if (model.empty()) {
        std::fprintf(stderr, "usage: mercan-server --model model.mercan [--host H] [--port P] [--threads N] [--ctx N] [--api-key K] [--system S]\n");
        return 2;
    }

    MercanChat chat;
    std::string err;
    std::fprintf(stderr, "loading %s ...\n", model.c_str());
    if (!chat.load(model, threads, ctx, 0, err)) { std::fprintf(stderr, "%s\n", err.c_str()); return 1; }

    MercanServer server(chat);
    MercanServer::Options opt;
    opt.host = host; opt.port = port; opt.api_key = key; opt.default_system = system; opt.stt_model = stt_model;
    const int bound = server.start(opt, err);
    if (bound < 0) { std::fprintf(stderr, "%s\n", err.c_str()); return 1; }
    server.set_ready(true);
    std::printf("mercan-server listening on http://%s:%d/v1 (%d threads)\n", host.c_str(), bound, chat.threads());
    std::fflush(stdout);

    std::signal(SIGINT, [](int) { g_quit = true; });
    std::signal(SIGTERM, [](int) { g_quit = true; });
    while (!g_quit) std::this_thread::sleep_for(std::chrono::milliseconds(200));
    server.stop();
    return 0;
}

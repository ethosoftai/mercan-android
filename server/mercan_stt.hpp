// Speech to text for the Mercan apps: whisper.cpp behind a tiny, thread-safe wrapper.
// The model is loaded on the first transcription and kept for later ones.
#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct whisper_context;

class MercanStt {
public:
    MercanStt() = default;
    ~MercanStt();

    void set_model(const std::string & path) { model_path_ = path; }
    void set_threads(int n) { n_threads_ = n; }
    bool available() const { return !model_path_.empty(); }

    // Decodes a RIFF/WAVE buffer (PCM 8/16/32-bit or float, any rate) to 16 kHz mono float.
    static bool wav_to_pcm(const std::string & wav, std::vector<float> & out, std::string & error);

    // Transcribes 16 kHz mono samples. `language` may be "tr", "auto", …
    std::string transcribe(const std::vector<float> & pcm, const std::string & language, std::string & error);

private:
    bool ensure_loaded(std::string & error);

    whisper_context * ctx_ = nullptr;
    std::string model_path_;
    int n_threads_ = 0;
    std::mutex mu_;
};

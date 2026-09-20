#include "mercan_stt.hpp"

#include "whisper.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>

namespace {

constexpr int kTargetRate = WHISPER_SAMPLE_RATE;   // 16 kHz

uint32_t rd32(const std::string & s, size_t at) {
    return (uint8_t) s[at] | ((uint8_t) s[at + 1] << 8) | ((uint8_t) s[at + 2] << 16) | ((uint32_t) (uint8_t) s[at + 3] << 24);
}
uint16_t rd16(const std::string & s, size_t at) {
    return (uint16_t) ((uint8_t) s[at] | ((uint8_t) s[at + 1] << 8));
}

void resample(std::vector<float> & samples, int from_rate) {
    if (from_rate == kTargetRate || samples.empty() || from_rate <= 0) return;
    const double ratio = (double) from_rate / kTargetRate;
    std::vector<float> out((size_t) (samples.size() / ratio));
    for (size_t i = 0; i < out.size(); ++i) {
        const double pos = i * ratio;
        const size_t idx = (size_t) pos;
        const size_t next = std::min(samples.size() - 1, idx + 1);
        out[i] = (float) (samples[idx] + (samples[next] - samples[idx]) * (pos - idx));
    }
    samples.swap(out);
}

} // namespace

MercanStt::~MercanStt() {
    if (ctx_) whisper_free(ctx_);
}

bool MercanStt::wav_to_pcm(const std::string & wav, std::vector<float> & out, std::string & error) {
    if (wav.size() < 44 || wav.compare(0, 4, "RIFF") != 0 || wav.compare(8, 4, "WAVE") != 0) {
        error = "desteklenmeyen ses biçimi (WAV bekleniyor)";
        return false;
    }
    uint16_t format = 1, channels = 1, bits = 16;
    uint32_t rate = kTargetRate;
    size_t data_at = 0, data_len = 0;

    for (size_t at = 12; at + 8 <= wav.size();) {
        const std::string id = wav.substr(at, 4);
        const uint32_t size = rd32(wav, at + 4);
        const size_t body = at + 8;
        if (id == "fmt " && body + 16 <= wav.size()) {
            format   = rd16(wav, body);
            channels = std::max<uint16_t>(1, rd16(wav, body + 2));
            rate     = rd32(wav, body + 4);
            bits     = rd16(wav, body + 14);
        } else if (id == "data") {
            data_at = body;
            data_len = std::min<size_t>(size, wav.size() - body);
            break;
        }
        at = body + size + (size & 1);
    }
    if (!data_at || !data_len) { error = "WAV içinde ses verisi yok"; return false; }
    if (format != 1 && format != 3) { error = "sıkıştırılmış WAV desteklenmiyor"; return false; }

    const size_t bytes = bits / 8;
    const size_t frames = data_len / (bytes * channels);
    out.assign(frames, 0.0f);
    for (size_t f = 0; f < frames; ++f) {
        double acc = 0.0;
        for (uint16_t c = 0; c < channels; ++c) {
            const size_t at = data_at + (f * channels + c) * bytes;
            if (format == 3 && bits == 32) {
                float v; std::memcpy(&v, wav.data() + at, 4); acc += v;
            } else if (bits == 16) {
                acc += (int16_t) rd16(wav, at) / 32768.0;
            } else if (bits == 32) {
                acc += (int32_t) rd32(wav, at) / 2147483648.0;
            } else if (bits == 8) {
                acc += ((uint8_t) wav[at] - 128) / 128.0;
            } else {
                error = "desteklenmeyen örnek genişliği";
                return false;
            }
        }
        out[f] = (float) std::max(-1.0, std::min(1.0, acc / channels));
    }
    resample(out, (int) rate);
    return true;
}

bool MercanStt::ensure_loaded(std::string & error) {
    if (ctx_) return true;
    if (model_path_.empty()) { error = "konuşma tanıma modeli ayarlanmadı"; return false; }
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    ctx_ = whisper_init_from_file_with_params(model_path_.c_str(), cparams);
    if (!ctx_) { error = "konuşma tanıma modeli yüklenemedi: " + model_path_; return false; }
    return true;
}

std::string MercanStt::transcribe(const std::vector<float> & pcm, const std::string & language, std::string & error) {
    std::lock_guard<std::mutex> lock(mu_);
    if (!ensure_loaded(error)) return {};
    if (pcm.size() < (size_t) kTargetRate / 10) { error = "kayıt çok kısa"; return {}; }

    // Near-silence makes whisper invent subtitle boilerplate; do not even ask it.
    float peak = 0.0f;
    double energy = 0.0;
    for (float v : pcm) { peak = std::max(peak, std::fabs(v)); energy += (double) v * v; }
    const double rms = std::sqrt(energy / pcm.size());
    if (peak < 0.02f || rms < 0.0025) return {};

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress   = false;
    wparams.print_realtime   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;
    wparams.no_timestamps    = true;
    wparams.single_segment   = false;
    wparams.suppress_nst     = true;
    wparams.language         = language.empty() || language == "auto" ? nullptr : language.c_str();
    wparams.n_threads        = n_threads_ > 0 ? n_threads_
                                              : (int) std::max(1u, std::min(8u, std::thread::hardware_concurrency() / 2));

    if (whisper_full(ctx_, wparams, pcm.data(), (int) pcm.size()) != 0) {
        error = "ses çözümlenemedi";
        return {};
    }
    std::string text;
    const int n = whisper_full_n_segments(ctx_);
    for (int i = 0; i < n; ++i) {
        const char * seg = whisper_full_get_segment_text(ctx_, i);
        if (seg) text += seg;
    }
    // whisper pads segments with a leading space and may emit bracketed noise tags.
    const size_t b = text.find_first_not_of(" \t\r\n");
    const size_t e = text.find_last_not_of(" \t\r\n");
    text = b == std::string::npos ? std::string() : text.substr(b, e - b + 1);
    for (const char * tag : { "[BLANK_AUDIO]", "(sessizlik)", "[Music]", "[MUSIC]", "[ Sessizlik ]" }) {
        for (size_t p = text.find(tag); p != std::string::npos; p = text.find(tag)) text.erase(p, std::strlen(tag));
    }

    // Quiet or noisy input also makes whisper fall back to YouTube/subtitle boilerplate.
    // Short results containing one of these phrases are noise, not speech.
    static const char * const kNoisePhrases[] = {
        "altyazı", "abone ol", "beğenmeyi", "beğenip", "izlediğiniz için teşekkür",
        "izlemeye çalışın", "kanalıma", "videoyu", "teşekkürler.", "iyi seyirler",
        "bir sonraki videoda", "altyazılar", "m.k.",
    };
    std::string lower;
    lower.reserve(text.size());
    for (char c : text) lower += (c >= 'A' && c <= 'Z') ? (char) (c - 'A' + 'a') : c;
    if (lower.size() < 80) {
        for (const char * phrase : kNoisePhrases) {
            if (lower.find(phrase) != std::string::npos) return {};
        }
    }
    return text;
}

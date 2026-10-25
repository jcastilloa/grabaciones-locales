/*
 * JNI glue written for Grabaciones Locales. LAME is dynamically linked from the LGPL-2.0-or-later
 * Prefab artifact com.fpliu.ndk.pkg.prefab.android.21:lame:3.100.
 */
#include <jni.h>
#include <lame/lame.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {
struct FileCloser { void operator()(FILE* file) const { if (file) std::fclose(file); } };
using FilePtr = std::unique_ptr<FILE, FileCloser>;

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass klass = env->FindClass("java/lang/IllegalStateException");
    if (klass) env->ThrowNew(klass, message);
}

std::string fromJavaString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

uint32_t readLe32(const unsigned char* p) {
    return static_cast<uint32_t>(p[0]) |
        (static_cast<uint32_t>(p[1]) << 8) |
        (static_cast<uint32_t>(p[2]) << 16) |
        (static_cast<uint32_t>(p[3]) << 24);
}

bool writeAll(FILE* file, const unsigned char* data, size_t length) {
    return std::fwrite(data, 1, length, file) == length;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_jcastillo_grabaciones_audio_LameMp3Encoder_encodeWavToMp3(
    JNIEnv* env, jobject /* this */, jstring inputPath, jstring outputPath, jint bitrateKbps) {
    if (!inputPath || !outputPath) {
        throwIllegalState(env, "WAV/MP3 path is missing");
        return 0;
    }
    const std::string inputName = fromJavaString(env, inputPath);
    const std::string outputName = fromJavaString(env, outputPath);
    FilePtr input(std::fopen(inputName.c_str(), "rb"));
    if (!input) {
        throwIllegalState(env, "Could not open the WAV intermediate");
        return 0;
    }
    FilePtr output(std::fopen(outputName.c_str(), "wb"));
    if (!output) {
        throwIllegalState(env, "Could not create the MP3 temporary file");
        return 0;
    }

    unsigned char header[44]{};
    if (std::fread(header, 1, sizeof(header), input.get()) != sizeof(header) ||
        std::memcmp(header, "RIFF", 4) != 0 || std::memcmp(header + 8, "WAVE", 4) != 0 ||
        std::memcmp(header + 12, "fmt ", 4) != 0 || std::memcmp(header + 36, "data", 4) != 0 ||
        header[20] != 1 || header[21] != 0 || header[22] != 1 || header[23] != 0 ||
        header[34] != 16 || header[35] != 0) {
        throwIllegalState(env, "WAV intermediate is not mono PCM16");
        return 0;
    }
    const uint32_t sampleRate = readLe32(header + 24);
    const uint32_t payloadBytes = readLe32(header + 40);
    if (sampleRate < 8000 || sampleRate > 48000 || payloadBytes < 2 || (payloadBytes & 1U) != 0U ||
        bitrateKbps < 32 || bitrateKbps > 320) {
        throwIllegalState(env, "WAV metadata or MP3 bitrate is invalid");
        return 0;
    }

    lame_t lame = lame_init();
    if (!lame) {
        throwIllegalState(env, "LAME initialization failed");
        return 0;
    }
    lame_set_num_channels(lame, 1);
    lame_set_in_samplerate(lame, static_cast<int>(sampleRate));
    lame_set_out_samplerate(lame, static_cast<int>(sampleRate));
    lame_set_brate(lame, bitrateKbps);
    lame_set_mode(lame, MONO);
    lame_set_VBR(lame, vbr_off);
    lame_set_quality(lame, 2);
    lame_set_num_samples(lame, payloadBytes / 2U);
    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        throwIllegalState(env, "LAME rejected the MP3 encoding parameters");
        return 0;
    }

    constexpr int kSamples = 8192;
    const size_t mp3Capacity = static_cast<size_t>(1.25 * kSamples + 7200);
    std::vector<short> pcm(kSamples);
    std::vector<unsigned char> mp3(mp3Capacity);
    uint32_t remaining = payloadBytes;
    jlong written = 0;
    bool ok = true;

    while (remaining > 0) {
        const size_t bytesToRead = std::min<size_t>(remaining, pcm.size() * 2U);
        const size_t bytesRead = std::fread(pcm.data(), 1, bytesToRead, input.get());
        if (bytesRead != bytesToRead || (bytesRead & 1U) != 0U) {
            ok = false;
            break;
        }
        const int sampleCount = static_cast<int>(bytesRead / 2U);
        const int encoded = lame_encode_buffer(
            lame, pcm.data(), pcm.data(), sampleCount, mp3.data(), static_cast<int>(mp3.size()));
        if (encoded < 0 || !writeAll(output.get(), mp3.data(), static_cast<size_t>(encoded))) {
            ok = false;
            break;
        }
        written += encoded;
        remaining -= static_cast<uint32_t>(bytesRead);
    }

    if (ok) {
        const int flushed = lame_encode_flush(lame, mp3.data(), static_cast<int>(mp3.size()));
        if (flushed < 0 || !writeAll(output.get(), mp3.data(), static_cast<size_t>(flushed))) {
            ok = false;
        } else {
            written += flushed;
        }
    }
    lame_close(lame);
    if (std::fflush(output.get()) != 0) ok = false;

    if (!ok || written < 128) {
        std::remove(outputName.c_str());
        throwIllegalState(env, "LAME failed to produce a complete MP3 file");
        return 0;
    }
    return written;
}

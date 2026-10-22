/*
    JNI bindings for me.magnum.melonds.pokewalker.PokewalkerNative.
    GPL-3.0-or-later, see PwCommon.h.
*/

#include <jni.h>

#include <string>
#include <vector>

#include "PokewalkerIR.h"

namespace
{

std::string ToStd(JNIEnv* env, jstring s)
{
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring ErrorOrNull(JNIEnv* env, bool ok, const std::string& error)
{
    if (ok) return nullptr;
    return env->NewStringUTF(error.empty() ? "unknown error" : error.c_str());
}

}

extern "C" {

JNIEXPORT void JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_configure(JNIEnv* env, jobject, jint mode, jstring eepromPath, jstring logPath, jint tcpPort, jboolean logPackets)
{
    pokewalker::ir::Config config;
    config.mode = static_cast<pokewalker::ir::Mode>(mode);
    config.eepromPath = ToStd(env, eepromPath);
    config.logPath = ToStd(env, logPath);
    config.tcpPort = tcpPort > 0 ? tcpPort : 8081;
    config.logPackets = logPackets == JNI_TRUE;
    pokewalker::ir::Configure(config);
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_note(JNIEnv* env, jobject, jstring text)
{
    pokewalker::ir::Note(ToStd(env, text));
}

JNIEXPORT jstring JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_getStatusJson(JNIEnv* env, jobject)
{
    std::string json = pokewalker::ir::StatusJson();
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_addSteps(JNIEnv* env, jobject, jint steps, jint daysAgo)
{
    std::string error;
    bool ok = steps >= 0 && pokewalker::ir::AddSteps(static_cast<pokewalker::u32>(steps), daysAgo, &error);
    if (steps < 0) error = "steps must not be negative";
    return ErrorOrNull(env, ok, error);
}

JNIEXPORT jstring JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_resetWalker(JNIEnv* env, jobject)
{
    std::string error;
    bool ok = pokewalker::ir::ResetWalker(&error);
    return ErrorOrNull(env, ok, error);
}

JNIEXPORT jstring JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_importImage(JNIEnv* env, jobject, jbyteArray data)
{
    std::string error;
    if (!data) return env->NewStringUTF("no data");
    jsize len = env->GetArrayLength(data);
    std::vector<pokewalker::u8> image(static_cast<size_t>(len));
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(image.data()));
    bool ok = pokewalker::ir::ImportImage(image, &error);
    return ErrorOrNull(env, ok, error);
}

static jstring JsonOrError(JNIEnv* env, bool ok, const std::string& json, const std::string& error)
{
    if (ok) return env->NewStringUTF(json.c_str());
    std::string out = "{\"error\":\"";
    for (char c : error) out += (c == '"' || c == '\\') ? '\'' : c;
    out += "\"}";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_radarEncounter(JNIEnv* env, jobject)
{
    int slot = -1;
    std::string json, error;
    bool ok = pokewalker::ir::RadarEncounter(&slot, &json, &error);
    return JsonOrError(env, ok, json, error);
}

JNIEXPORT jstring JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_storeCaught(JNIEnv* env, jobject, jint slot)
{
    std::string error;
    bool ok = pokewalker::ir::StoreCaught(slot, &error);
    return ErrorOrNull(env, ok, error);
}

JNIEXPORT jstring JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_dowsingStart(JNIEnv* env, jobject)
{
    pokewalker::u16 item = 0;
    std::string json, error;
    bool ok = pokewalker::ir::DowsingStart(&item, &json, &error);
    return JsonOrError(env, ok, json, error);
}

JNIEXPORT jstring JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_storeDowsed(JNIEnv* env, jobject, jint item)
{
    std::string error;
    bool ok = item > 0 && item <= 0xFFFF && pokewalker::ir::StoreDowsed(static_cast<pokewalker::u16>(item), &error);
    if (!(item > 0 && item <= 0xFFFF)) error = "invalid item";
    return ErrorOrNull(env, ok, error);
}

JNIEXPORT jbyteArray JNICALL
Java_me_magnum_melonds_pokewalker_PokewalkerNative_exportImage(JNIEnv* env, jobject)
{
    std::vector<pokewalker::u8> image;
    std::string error;
    if (!pokewalker::ir::ExportImage(&image, &error)) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(image.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(image.size()), reinterpret_cast<const jbyte*>(image.data()));
    return out;
}

}

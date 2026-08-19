#include <jni.h>
#include <string>
#include <vector>
#include "ggwave.h"

// In GGWave, instances are just integer IDs. We use -1 to mean "uninitialized".
static ggwave_Instance ggwaveInstance = -1;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_audio_GGWaveEngine_initNative(JNIEnv *env, jobject thiz, jint sampleRate) {
    if (ggwaveInstance != -1) {
        ggwave_free(ggwaveInstance);
    }
    
    ggwave_Parameters params = ggwave_getDefaultParameters();
    params.sampleRateInp = sampleRate;
    params.sampleRateOut = sampleRate;
    params.sampleRate = sampleRate;
    params.samplesPerFrame = 1024;
    
    ggwaveInstance = ggwave_init(params);
    
    // ggwave_init returns a positive integer if successful, or < 0 if it fails.
    return (ggwaveInstance != -1);
}

JNIEXPORT jshortArray JNICALL
Java_com_example_audio_GGWaveEngine_encodeNative(JNIEnv *env, jobject thiz, jbyteArray payload, jint protocolId, jint volume) {
    if (ggwaveInstance == -1) return nullptr;

    jsize payloadLen = env->GetArrayLength(payload);
    jbyte *payloadBytes = env->GetByteArrayElements(payload, nullptr);

    int nSamples = ggwave_encode(ggwaveInstance, (const char *)payloadBytes, payloadLen, 
                                 (ggwave_ProtocolId)protocolId, volume, nullptr, 1);

    if (nSamples <= 0) {
        env->ReleaseByteArrayElements(payload, payloadBytes, JNI_ABORT);
        return nullptr;
    }

    std::vector<short> outputWave(nSamples);
    ggwave_encode(ggwaveInstance, (const char *)payloadBytes, payloadLen, 
                  (ggwave_ProtocolId)protocolId, volume, outputWave.data(), 0);

    env->ReleaseByteArrayElements(payload, payloadBytes, JNI_ABORT);

    jshortArray result = env->NewShortArray(nSamples);
    env->SetShortArrayRegion(result, 0, nSamples, outputWave.data());
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_audio_GGWaveEngine_decodeNative(JNIEnv *env, jobject thiz, jshortArray pcmBuffer, jint length) {
    if (ggwaveInstance == -1 || length <= 0) return nullptr;

    jshort *pcm = env->GetShortArrayElements(pcmBuffer, nullptr);
    
    char outputBuffer[2048];
    int rxBytes = ggwave_decode(ggwaveInstance, (const char *)pcm, length * sizeof(short), outputBuffer);

    env->ReleaseShortArrayElements(pcmBuffer, pcm, JNI_ABORT);

    if (rxBytes > 0) {
        jbyteArray result = env->NewByteArray(rxBytes);
        env->SetByteArrayRegion(result, 0, rxBytes, (const jbyte *)outputBuffer);
        return result;
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_example_audio_GGWaveEngine_releaseNative(JNIEnv *env, jobject thiz) {
    if (ggwaveInstance != -1) {
        ggwave_free(ggwaveInstance);
        ggwaveInstance = -1;
    }
}

}
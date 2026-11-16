package com.zeko.player

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable
import java.io.File

data class SpeechUpdate(val text: String, val isEndpoint: Boolean)

/** Local streaming English ASR. Input is mono PCM16 at 16 kHz. */
class SherpaOnlineSpeechRecognizer(modelDirectory: File) : Closeable {
    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream
    private var closed = false

    init {
        val encoder = File(modelDirectory, "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx")
        val decoder = File(modelDirectory, "decoder-epoch-99-avg-1-chunk-16-left-128.onnx")
        val joiner = File(modelDirectory, "joiner-epoch-99-avg-1-chunk-16-left-128.onnx")
        val tokens = File(modelDirectory, "tokens.txt")
        require(encoder.isFile && decoder.isFile && joiner.isFile && tokens.isFile) {
            "Sherpa model files are incomplete"
        }
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                joiner = joiner.absolutePath
            ),
            tokens = tokens.absolutePath,
            numThreads = 2,
            modelType = "zipformer2"
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 0.8f, 0.0f),
                rule2 = EndpointRule(true, 0.6f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 12.0f)
            ),
            enableEndpoint = true
        )
        recognizer = OnlineRecognizer(config = config)
        stream = recognizer.createStream()
    }

    @Synchronized
    fun acceptAudio(pcm16: ByteArray): SpeechUpdate {
        check(!closed) { "Speech recognizer is closed" }
        val sampleCount = pcm16.size / 2
        if (sampleCount == 0) return SpeechUpdate("", false)
        val samples = FloatArray(sampleCount)
        var offset = 0
        for (i in 0 until sampleCount) {
            val value = (pcm16[offset].toInt() and 0xFF) or
                (pcm16[offset + 1].toInt() shl 8)
            samples[i] = value.toShort().toFloat() / 32768.0f
            offset += 2
        }
        stream.acceptWaveform(samples, 16000)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val text = recognizer.getResult(stream).text.trim()
        val endpoint = recognizer.isEndpoint(stream)
        if (endpoint) recognizer.reset(stream)
        return SpeechUpdate(text, endpoint)
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            stream.release()
            recognizer.release()
        }
    }
}

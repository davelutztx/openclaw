// Local Whisper provider buffers relay audio and transcribes it through Dave's AI PC service.
import {
  type RealtimeTranscriptionProviderConfig,
  type RealtimeTranscriptionProviderPlugin,
  type RealtimeTranscriptionSession,
  type RealtimeTranscriptionSessionCreateRequest,
} from "openclaw/plugin-sdk/realtime-transcription";
import {
  asOptionalRecord as readRecord,
  normalizeOptionalString,
  parseFiniteNumber as readFiniteNumber,
} from "openclaw/plugin-sdk/string-coerce-runtime";

type LocalWhisperTranscriptionProviderConfig = {
  baseUrl?: string;
  timeoutMs?: number;
  maxBufferedBytes?: number;
  language?: string;
  beamSize?: number;
  vadFilter?: boolean;
};

type LocalWhisperTranscriptionResult = {
  ok?: boolean;
  text?: string;
  detail?: unknown;
};

const LOCAL_WHISPER_DEFAULT_BASE_URL = "http://192.168.1.152:8765";
const LOCAL_WHISPER_DEFAULT_TIMEOUT_MS = 120_000;
const LOCAL_WHISPER_DEFAULT_MAX_BUFFERED_BYTES = 8 * 1024 * 1024;
const LOCAL_WHISPER_SAMPLE_RATE_HZ = 8000;
const LOCAL_WHISPER_CHANNELS = 1;
const LOCAL_WHISPER_BITS_PER_SAMPLE = 16;

function readNestedLocalWhisperConfig(rawConfig: RealtimeTranscriptionProviderConfig) {
  const raw = readRecord(rawConfig);
  const providers = readRecord(raw?.providers);
  return (
    readRecord(providers?.["ai-pc-whisper"]) ??
    readRecord(providers?.aiPcWhisper) ??
    readRecord(raw?.["ai-pc-whisper"]) ??
    readRecord(raw?.aiPcWhisper) ??
    raw ??
    {}
  );
}

function normalizePositiveInteger(value: unknown): number | undefined {
  const parsed = readFiniteNumber(value);
  return parsed !== undefined && Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

function normalizeOptionalBoolean(value: unknown): boolean | undefined {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value !== "string") {
    return undefined;
  }
  const normalized = value.trim().toLowerCase();
  if (["1", "true", "yes", "on"].includes(normalized)) {
    return true;
  }
  if (["0", "false", "no", "off"].includes(normalized)) {
    return false;
  }
  return undefined;
}

function normalizeBaseUrl(value: unknown): string {
  const raw =
    normalizeOptionalString(value) ??
    normalizeOptionalString(process.env.OPENCLAW_LOCAL_WHISPER_BASE_URL) ??
    LOCAL_WHISPER_DEFAULT_BASE_URL;
  return raw.replace(/\/+$/, "");
}

function normalizeProviderConfig(
  config: RealtimeTranscriptionProviderConfig,
): LocalWhisperTranscriptionProviderConfig {
  const raw = readNestedLocalWhisperConfig(config);
  return {
    baseUrl: normalizeOptionalString(raw.baseUrl ?? raw.url),
    timeoutMs: normalizePositiveInteger(raw.timeoutMs ?? raw.timeout_ms),
    maxBufferedBytes: normalizePositiveInteger(raw.maxBufferedBytes ?? raw.max_buffered_bytes),
    language: normalizeOptionalString(raw.language),
    beamSize: normalizePositiveInteger(raw.beamSize ?? raw.beam_size),
    vadFilter: normalizeOptionalBoolean(raw.vadFilter ?? raw.vad_filter),
  };
}

function decodePcmuSample(value: number): number {
  const pcmu = ~value & 0xff;
  const sign = pcmu & 0x80;
  const exponent = (pcmu >> 4) & 0x07;
  const mantissa = pcmu & 0x0f;
  let sample = ((mantissa << 3) + 0x84) << exponent;
  sample -= 0x84;
  return sign ? -sample : sample;
}

function buildPcm16WavFromPcmu(audio: Buffer): Buffer {
  const dataSize = audio.byteLength * 2;
  const wav = Buffer.alloc(44 + dataSize);
  wav.write("RIFF", 0, "ascii");
  wav.writeUInt32LE(36 + dataSize, 4);
  wav.write("WAVE", 8, "ascii");
  wav.write("fmt ", 12, "ascii");
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(LOCAL_WHISPER_CHANNELS, 22);
  wav.writeUInt32LE(LOCAL_WHISPER_SAMPLE_RATE_HZ, 24);
  wav.writeUInt32LE(
    LOCAL_WHISPER_SAMPLE_RATE_HZ * LOCAL_WHISPER_CHANNELS * (LOCAL_WHISPER_BITS_PER_SAMPLE / 8),
    28,
  );
  wav.writeUInt16LE(LOCAL_WHISPER_CHANNELS * (LOCAL_WHISPER_BITS_PER_SAMPLE / 8), 32);
  wav.writeUInt16LE(LOCAL_WHISPER_BITS_PER_SAMPLE, 34);
  wav.write("data", 36, "ascii");
  wav.writeUInt32LE(dataSize, 40);
  for (let index = 0; index < audio.byteLength; index += 1) {
    const sample = Math.max(-32768, Math.min(32767, decodePcmuSample(audio[index])));
    wav.writeInt16LE(sample, 44 + index * 2);
  }
  return wav;
}

function readErrorDetail(value: unknown): string | undefined {
  if (typeof value === "string") {
    return value;
  }
  const record = readRecord(value);
  return normalizeOptionalString(record?.detail ?? record?.message ?? record?.error);
}

function createLocalWhisperTranscriptionSession(
  config: RealtimeTranscriptionSessionCreateRequest & {
    baseUrl: string;
    timeoutMs: number;
    maxBufferedBytes: number;
    language?: string;
    beamSize?: number;
    vadFilter?: boolean;
  },
): RealtimeTranscriptionSession {
  let connected = false;
  let closed = false;
  let speechStarted = false;
  let finishPromise: Promise<void> | undefined;
  const chunks: Buffer[] = [];
  let bufferedBytes = 0;

  const transcribe = async (): Promise<void> => {
    if (bufferedBytes === 0) {
      return;
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.timeoutMs);
    try {
      const audio = Buffer.concat(chunks, bufferedBytes);
      const wav = buildPcm16WavFromPcmu(audio);
      const form = new FormData();
      form.append("file", new Blob([new Uint8Array(wav)], { type: "audio/wav" }), "dictation.wav");
      if (config.language) {
        form.append("language", config.language);
      }
      if (config.beamSize !== undefined) {
        form.append("beam_size", String(config.beamSize));
      }
      if (config.vadFilter !== undefined) {
        form.append("vad_filter", String(config.vadFilter));
      }
      const response = await fetch(`${config.baseUrl}/transcribe`, {
        method: "POST",
        body: form,
        signal: controller.signal,
      });
      const body = (await response.json().catch(() => ({}))) as LocalWhisperTranscriptionResult;
      if (!response.ok || body.ok === false) {
        throw new Error(
          readErrorDetail(body.detail ?? body) ??
            `AI PC Whisper transcription failed (${response.status})`,
        );
      }
      const text = normalizeOptionalString(body.text);
      if (text) {
        config.onTranscript?.(text);
      }
    } catch (error) {
      const message =
        error instanceof Error && error.name === "AbortError"
          ? "AI PC Whisper transcription timed out"
          : error instanceof Error
            ? error.message
            : String(error);
      const normalizedError = new Error(message);
      config.onError?.(normalizedError);
      throw normalizedError;
    } finally {
      clearTimeout(timeout);
    }
  };

  return {
    async connect() {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), Math.min(config.timeoutMs, 10_000));
      try {
        const response = await fetch(`${config.baseUrl}/health`, { signal: controller.signal });
        if (!response.ok) {
          throw new Error(`AI PC Whisper service health failed (${response.status})`);
        }
        connected = true;
      } catch (error) {
        const message =
          error instanceof Error && error.name === "AbortError"
            ? "AI PC Whisper service health timed out"
            : error instanceof Error
              ? error.message
              : String(error);
        throw new Error(message);
      } finally {
        clearTimeout(timeout);
      }
    },
    sendAudio(audio: Buffer) {
      if (closed) {
        return;
      }
      if (!speechStarted && audio.byteLength > 0) {
        speechStarted = true;
        config.onSpeechStart?.();
      }
      if (bufferedBytes + audio.byteLength > config.maxBufferedBytes) {
        const error = new Error("AI PC Whisper transcription buffer is full");
        config.onError?.(error);
        closed = true;
        return;
      }
      chunks.push(Buffer.from(audio));
      bufferedBytes += audio.byteLength;
    },
    finish() {
      if (!finishPromise) {
        finishPromise = transcribe();
      }
      return finishPromise;
    },
    close() {
      closed = true;
      connected = false;
    },
    isConnected() {
      return connected && !closed;
    },
  };
}

export function buildLocalWhisperRealtimeTranscriptionProvider(): RealtimeTranscriptionProviderPlugin {
  return {
    id: "ai-pc-whisper",
    label: "AI PC Whisper",
    aliases: ["local-whisper", "faster-whisper", "whisper-local"],
    defaultModel: "large-v3",
    autoSelectOrder: 5,
    resolveConfig: ({ rawConfig }) => normalizeProviderConfig(rawConfig),
    isConfigured: ({ providerConfig }) => {
      const config = normalizeProviderConfig(providerConfig);
      return Boolean(config.baseUrl || process.env.OPENCLAW_LOCAL_WHISPER_BASE_URL);
    },
    createSession: (req) => {
      const config = normalizeProviderConfig(req.providerConfig);
      return createLocalWhisperTranscriptionSession({
        ...req,
        baseUrl: normalizeBaseUrl(config.baseUrl),
        timeoutMs: config.timeoutMs ?? LOCAL_WHISPER_DEFAULT_TIMEOUT_MS,
        maxBufferedBytes: config.maxBufferedBytes ?? LOCAL_WHISPER_DEFAULT_MAX_BUFFERED_BYTES,
        language: config.language,
        beamSize: config.beamSize,
        vadFilter: config.vadFilter,
      });
    },
  };
}

export const testing = {
  buildPcm16WavFromPcmu,
  decodePcmuSample,
  normalizeProviderConfig,
};
export { testing as __testing };

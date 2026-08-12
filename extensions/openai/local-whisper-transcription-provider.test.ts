// Local Whisper tests cover batch STT config and audio wrapping behavior.
import { describe, expect, it } from "vitest";
import {
  buildLocalWhisperRealtimeTranscriptionProvider,
  __testing,
} from "./local-whisper-transcription-provider.js";

describe("buildLocalWhisperRealtimeTranscriptionProvider", () => {
  it("normalizes configured AI PC Whisper provider settings", () => {
    const provider = buildLocalWhisperRealtimeTranscriptionProvider();
    const resolved = provider.resolveConfig?.({
      cfg: {} as never,
      rawConfig: {
        providers: {
          "ai-pc-whisper": {
            baseUrl: "http://192.168.1.152:8765/",
            timeoutMs: "30000",
            maxBufferedBytes: "1024",
            language: "en",
            beamSize: "3",
            vadFilter: "false",
          },
        },
      },
    });

    expect(resolved).toEqual({
      baseUrl: "http://192.168.1.152:8765/",
      timeoutMs: 30000,
      maxBufferedBytes: 1024,
      language: "en",
      beamSize: 3,
      vadFilter: false,
    });
  });

  it("is configured when a base URL is present", () => {
    const provider = buildLocalWhisperRealtimeTranscriptionProvider();

    expect(
      provider.isConfigured({ providerConfig: { baseUrl: "http://192.168.1.152:8765" } }),
    ).toBe(true);
    expect(provider.isConfigured({ providerConfig: {} })).toBe(false);
  });

  it("wraps 8 kHz mu-law audio in a PCM WAV container", () => {
    const wav = __testing.buildPcm16WavFromPcmu(Buffer.from([0xff, 0x7f]));

    expect(wav.subarray(0, 4).toString("ascii")).toBe("RIFF");
    expect(wav.subarray(8, 12).toString("ascii")).toBe("WAVE");
    expect(wav.readUInt16LE(20)).toBe(1);
    expect(wav.readUInt16LE(22)).toBe(1);
    expect(wav.readUInt32LE(24)).toBe(8000);
    expect(wav.readUInt16LE(34)).toBe(16);
    expect(wav.subarray(36, 40).toString("ascii")).toBe("data");
    expect(wav.readUInt32LE(40)).toBe(4);
  });
});

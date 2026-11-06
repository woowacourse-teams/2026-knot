import { afterEach, describe, expect, it } from "vitest";

import {
  deleteRecordingChunks,
  deleteRecordingChunksThrough,
  readRecordingChunks,
  saveRecordingChunk,
} from "./recordingChunkArchive";

const RECORDING_ID = 10;
const OTHER_RECORDING_ID = 11;

const chunk = (sequence: number, byte = 0, recordingId = RECORDING_ID) => ({
  recordingId,
  sequence,
  pcm: new Uint8Array([byte]).buffer,
});

const sequencesOf = async (recordingId = RECORDING_ID) =>
  (await readRecordingChunks(recordingId)).map(({ sequence }) => sequence);

describe("recordingChunkArchive", () => {
  afterEach(async () => {
    await deleteRecordingChunks(RECORDING_ID);
    await deleteRecordingChunks(OTHER_RECORDING_ID);
  });

  it("보관한 조각을 번호 순서로 읽는다", async () => {
    await saveRecordingChunk(chunk(2));
    await saveRecordingChunk(chunk(10));
    await saveRecordingChunk(chunk(1));

    expect(await sequencesOf()).toEqual([1, 2, 10]);
  });

  it("보관한 바이트를 그대로 돌려준다", async () => {
    await saveRecordingChunk(chunk(1, 7));

    const [{ pcm }] = await readRecordingChunks(RECORDING_ID);

    expect(new Uint8Array(pcm)).toEqual(new Uint8Array([7]));
  });

  it("같은 번호는 덮어쓴다", async () => {
    await saveRecordingChunk(chunk(1, 1));
    await saveRecordingChunk(chunk(1, 2));

    const chunks = await readRecordingChunks(RECORDING_ID);

    expect(chunks).toHaveLength(1);
    expect(new Uint8Array(chunks[0].pcm)).toEqual(new Uint8Array([2]));
  });

  it("빈틈없이 확인된 번호까지만 지운다", async () => {
    await saveRecordingChunk(chunk(1));
    await saveRecordingChunk(chunk(2));
    await saveRecordingChunk(chunk(3));

    await deleteRecordingChunksThrough(RECORDING_ID, 2);

    expect(await sequencesOf()).toEqual([3]);
  });

  it("녹음을 지워도 다른 녹음의 조각은 남는다", async () => {
    await saveRecordingChunk(chunk(1));
    await saveRecordingChunk(chunk(1, 0, OTHER_RECORDING_ID));

    await deleteRecordingChunks(RECORDING_ID);

    expect(await sequencesOf()).toEqual([]);
    expect(await sequencesOf(OTHER_RECORDING_ID)).toEqual([1]);
  });
});

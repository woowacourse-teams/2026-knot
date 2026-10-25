#!/usr/bin/env python3
"""Bounded window diarization and real speaker embeddings using the sherpa C API.

Native output is suppressed; only the private result file contains vectors.
The parent process supervisor owns cancellation of this child.
"""
import array
import ctypes as c
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import time
import wave

# windowResult.ts의 MAXIMUM_WINDOW_FRAMES와 같은 130초 상한
MAXIMUM_WINDOW_FRAMES = 130 * 16000

class Pyannote(c.Structure):
    _fields_=[('model',c.c_char_p),('window_shift_ratio',c.c_float)]
class Segmentation(c.Structure):
    _fields_=[('pyannote',Pyannote),('num_threads',c.c_int32),('debug',c.c_int32),('provider',c.c_char_p)]
class Embedding(c.Structure):
    _fields_=[('model',c.c_char_p),('num_threads',c.c_int32),('debug',c.c_int32),('provider',c.c_char_p)]
class Clustering(c.Structure):
    _fields_=[('num_clusters',c.c_int32),('threshold',c.c_float)]
class Config(c.Structure):
    _fields_=[('segmentation',Segmentation),('embedding',Embedding),('clustering',Clustering),('min_duration_on',c.c_float),('min_duration_off',c.c_float)]
class Segment(c.Structure):
    _fields_=[('start',c.c_float),('end',c.c_float),('speaker',c.c_int32)]

class Native:
    def __init__(self, profile):
        self.lib = c.CDLL(profile['sherpaLibrary'])
        fp, pointer = c.POINTER(c.c_float), c.c_void_p
        signatures = {
            'CreateOfflineSpeakerDiarization': ([c.POINTER(Config)], pointer),
            'DestroyOfflineSpeakerDiarization': ([pointer], None),
            'OfflineSpeakerDiarizationGetSampleRate': ([pointer], c.c_int32),
            'OfflineSpeakerDiarizationProcess': ([pointer, fp, c.c_int32], pointer),
            'OfflineSpeakerDiarizationResultGetNumSegments': ([pointer], c.c_int32),
            'OfflineSpeakerDiarizationResultGetNumSpeakers': ([pointer], c.c_int32),
            'OfflineSpeakerDiarizationResultSortByStartTime': ([pointer], c.POINTER(Segment)),
            'OfflineSpeakerDiarizationDestroySegment': ([c.POINTER(Segment)], None),
            'OfflineSpeakerDiarizationDestroyResult': ([pointer], None),
            'CreateSpeakerEmbeddingExtractor': ([c.POINTER(Embedding)], pointer),
            'DestroySpeakerEmbeddingExtractor': ([pointer], None),
            'SpeakerEmbeddingExtractorDim': ([pointer], c.c_int32),
            'SpeakerEmbeddingExtractorCreateStream': ([pointer], pointer),
            'OnlineStreamAcceptWaveform': ([pointer, c.c_int32, fp, c.c_int32], None),
            'OnlineStreamInputFinished': ([pointer], None),
            'SpeakerEmbeddingExtractorIsReady': ([pointer, pointer], c.c_int32),
            'SpeakerEmbeddingExtractorComputeEmbedding': ([pointer, pointer], fp),
            'SpeakerEmbeddingExtractorDestroyEmbedding': ([fp], None),
            'DestroyOnlineStream': ([pointer], None),
            'GetVersionStr': ([], c.c_char_p), 'GetGitSha1': ([], c.c_char_p),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.lib, 'SherpaOnnx' + name)
            function.argtypes, function.restype = args, result
            setattr(self, name, function)
        if self.GetVersionStr().decode() != '1.13.6':
            raise RuntimeError('UNSUPPORTED_SHERPA_ABI')
        threads = profile['diarizeThreads']
        self.config = Config(Segmentation(Pyannote(profile['segmentationModel'].encode(), .1),
            threads, 0, b'cpu'), Embedding(profile['embeddingModel'].encode(), threads, 0, b'cpu'),
            Clustering(profile.get('speakerCount', -1), .8), .3, .5)
        self.embedding_config = Embedding(profile['embeddingModel'].encode(), threads, 0, b'cpu')
        self.diarizer = self.extractor = None
        try:
            self.diarizer = self.CreateOfflineSpeakerDiarization(c.byref(self.config))
            if not self.diarizer:
                raise RuntimeError('diarizer initialization failed')
            self.extractor = self.CreateSpeakerEmbeddingExtractor(c.byref(self.embedding_config))
            if not self.extractor:
                raise RuntimeError('embedding extractor initialization failed')
            self.dimension = self.SpeakerEmbeddingExtractorDim(self.extractor)
            if self.dimension != 512:
                raise RuntimeError('invalid embedding dimension')
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.extractor:
            self.DestroySpeakerEmbeddingExtractor(self.extractor)
            self.extractor = None
        if self.diarizer:
            self.DestroyOfflineSpeakerDiarization(self.diarizer)
            self.diarizer = None

    def embedding(self, floats, rate):
        stream, vector = None, None
        try:
            stream = self.SpeakerEmbeddingExtractorCreateStream(self.extractor)
            if not stream:
                raise RuntimeError('embedding stream initialization failed')
            samples = (c.c_float * len(floats)).from_buffer(floats)
            self.OnlineStreamAcceptWaveform(stream, rate, samples, len(floats))
            self.OnlineStreamInputFinished(stream)
            if not self.SpeakerEmbeddingExtractorIsReady(self.extractor, stream):
                return {'status': 'insufficient_samples', 'vector': None}
            vector = self.SpeakerEmbeddingExtractorComputeEmbedding(self.extractor, stream)
            if not vector:
                raise RuntimeError('embedding computation returned null')
            values = [float(vector[i]) for i in range(self.dimension)]
            if not all(math.isfinite(value) for value in values):
                raise RuntimeError('nonfinite embedding')
            return {'status': 'success', 'vector': values}
        finally:
            if vector:
                self.SpeakerEmbeddingExtractorDestroyEmbedding(vector)
            if stream:
                self.DestroyOnlineStream(stream)

    def process(self, floats, rate, input_start):
        result, segments = None, None
        try:
            samples = (c.c_float * len(floats)).from_buffer(floats)
            start = time.perf_counter()
            result = self.OfflineSpeakerDiarizationProcess(self.diarizer, samples, len(floats))
            if not result:
                raise RuntimeError('diarization returned null')
            count = self.OfflineSpeakerDiarizationResultGetNumSegments(result)
            if not 0 <= count <= 100000:
                raise RuntimeError('invalid segment count')
            segments = self.OfflineSpeakerDiarizationResultSortByStartTime(result)
            if count and not segments:
                raise RuntimeError('null segment array')
            diarization_seconds = time.perf_counter()-start
            output = []
            for index in range(count):
                segment = segments[index]
                if not (math.isfinite(segment.start) and math.isfinite(segment.end)):
                    raise RuntimeError('nonfinite segment boundary')
                first = max(0, min(len(floats), round(segment.start*rate)))
                last = max(first, min(len(floats), round(segment.end*rate)))
                start = time.perf_counter()
                embedding = self.embedding(floats[first:last], rate) if last > first else {
                    'status': 'empty_segment', 'vector': None}
                output.append({'speaker_local': segment.speaker,
                    'start_sec': input_start/rate+first/rate,
                    'end_sec': input_start/rate+last/rate,
                    'local_start_sec': float(segment.start), 'local_end_sec': float(segment.end),
                    'embedding_seconds': time.perf_counter()-start, 'embedding': embedding})
            return {'segments': output, 'speaker_count': self.OfflineSpeakerDiarizationResultGetNumSpeakers(result),
                    'diarization_seconds': diarization_seconds}
        finally:
            if segments:
                self.OfflineSpeakerDiarizationDestroySegment(segments)
            if result:
                self.OfflineSpeakerDiarizationDestroyResult(result)


def aggregate(segments, dimension=512):
    grouped = {}
    for segment in segments:
        first = round(segment['start_sec'] * 16000)
        last = round(segment['end_sec'] * 16000)
        if first < 0 or last <= first:
            continue
        label = 'speaker_' + str(segment['speaker_local'])
        row = grouped.setdefault(label, {'speakerId': label, 'embedding': [0.0]*dimension,
                                          'voicedFrames': 0, 'weight': 0})
        frames = last-first
        row['voicedFrames'] += frames
        vector = segment['embedding']['vector']
        if vector is not None:
            if len(vector) != dimension or not all(math.isfinite(x) for x in vector):
                raise ValueError('INVALID_EMBEDDING')
            row['weight'] += frames
            row['embedding'] = [a+b*frames for a,b in zip(row['embedding'], vector)]
    output = []
    for label in sorted(grouped):
        row = grouped[label]
        weight = row.pop('weight')
        row['embedding'] = [x/weight for x in row['embedding']] if weight else []
        output.append(row)
    return output


def execute(manifest):
    profile = dict(manifest['profile'])
    if manifest.get('speakerCount') is not None:
        profile['speakerCount'] = manifest['speakerCount']
    with wave.open(manifest['audioPath'], 'rb') as wav:
        frames = wav.getnframes()
        if (wav.getframerate() != 16000 or wav.getnchannels() != 1
                or wav.getsampwidth() != 2 or not 0 < frames <= MAXIMUM_WINDOW_FRAMES):
            raise ValueError('INVALID_AUDIO')
        if manifest.get('window') and frames != (manifest['window']['inputEndFrame'] - manifest['window']['inputStartFrame']):
            raise ValueError('WINDOW_FRAME_MISMATCH')
        pcm = array.array('h', wav.readframes(frames))
        if len(pcm) != frames:
            raise ValueError('TRUNCATED_AUDIO')
        if sys.byteorder != 'little':
            pcm.byteswap()
    native = Native(profile)
    try:
        value = native.process(array.array('f', (x/32768.0 for x in pcm)), 16000, 0)
        segments = value['segments']
        digest = hashlib.sha256()
        for key in ('segmentationModel', 'embeddingModel'):
            with open(profile[key], 'rb') as source:
                for block in iter(lambda: source.read(1024*1024), b''):
                    digest.update(block)
        model_hash = digest.hexdigest()
        return {'modelVersion': native.GetVersionStr().decode() + ':' + native.GetGitSha1().decode() + ':' + model_hash,
                'speakers': aggregate(segments),
                'segments': [{'start': s['start_sec'], 'end': s['end_sec'],
                              'speaker': 'speaker_' + str(s['speaker_local'])} for s in segments]}
    finally:
        native.close()


def main():
    if len(sys.argv) != 3:
        raise ValueError('INVALID_ARGUMENTS')
    # Discard native model diagnostics, which can contain local paths.
    null = os.open(os.devnull, os.O_WRONLY)
    os.dup2(null, 1)
    os.dup2(null, 2)
    os.close(null)
    result = execute(json.loads(Path(sys.argv[1]).read_text()))
    fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(result, output, allow_nan=False)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit(1)

import importlib.util
from pathlib import Path
import tempfile
import unittest
import wave

spec = importlib.util.spec_from_file_location('window_diarization', Path(__file__).with_name('windowDiarization.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class AggregationTest(unittest.TestCase):
    def segment(self, start, end, vector):
        return {'speaker_local': 0, 'start_sec': start, 'end_sec': end,
                'embedding': {'vector': vector}}

    def test_duration_weighted_vectors_and_missing_evidence(self):
        result = module.aggregate([self.segment(0,1,[1.0]*512), self.segment(1,3,[0.0]*512)])
        self.assertEqual(48000, result[0]['voicedFrames'])
        self.assertAlmostEqual(1/3, result[0]['embedding'][0])
        self.assertEqual([], module.aggregate([self.segment(0,1,None)])[0]['embedding'])

    def test_reject_nonfinite_and_wrong_dimension(self):
        for vector in ([1.0], [float('nan')]*512):
            with self.assertRaises(ValueError):
                module.aggregate([self.segment(0,1,vector)])

class WindowLengthTest(unittest.TestCase):
    def execute(self, frames):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'window.wav'
            with wave.open(str(path), 'wb') as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16000)
                wav.writeframes(bytes(frames * 2))
            module.execute({'audioPath': str(path), 'profile': {},
                            'window': {'inputStartFrame': 0, 'inputEndFrame': frames}})

    def test_accept_two_minutes_with_lookback_and_reject_longer(self):
        class Reached(Exception):
            pass

        def native(profile):
            raise Reached()

        original = module.Native
        module.Native = native
        try:
            with self.assertRaises(Reached):
                self.execute(130 * 16000)
            with self.assertRaisesRegex(ValueError, 'INVALID_AUDIO'):
                self.execute(130 * 16000 + 1)
        finally:
            module.Native = original

if __name__ == '__main__':
    unittest.main()

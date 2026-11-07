import itertools
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from measure import words, word_errors, rtf


class MeasureTests(unittest.TestCase):
    def test_punctuation_case_unicode(self):
        self.assertEqual(words('ДА, нет! е\u0308лка 12,5'), ['да', 'нет', 'ёлка', '12', '5'])

    def test_preserves_numbers_and_negation(self):
        result = word_errors('не надо 14 папок', 'надо 12 папок')
        self.assertEqual((result['substitutions'], result['deletions'], result['wer']), (1, 1, .5))

    def test_no_number_or_yo_equivalence(self):
        self.assertEqual(word_errors('ёлка три', 'елка 3')['errors'], 2)

    def test_exact(self):
        self.assertEqual(word_errors('Да, НЕТ.', 'да нет')['wer'], 0)

    def test_insertions_can_exceed_one(self):
        self.assertEqual(word_errors('а', 'а б в')['wer'], 2)

    def test_empty_reference(self):
        result = word_errors('', 'два слова')
        self.assertIsNone(result['wer'])
        self.assertEqual(result['insertions'], 2)

    def test_empty_hypothesis(self):
        self.assertEqual(word_errors('два слова', '')['deletions'], 2)

    def test_empty_both(self):
        self.assertEqual(word_errors('', '')['errors'], 0)

    def test_rtf(self):
        self.assertEqual(rtf(1500, 3000), .5)

    def test_bad_timings(self):
        for pair in [(1, 0), (-1, 2), (True, 2), (1, float('nan')), (float('inf'), 2)]:
            with self.subTest(pair=pair), self.assertRaises(ValueError):
                rtf(*pair)

    def test_edit_distance_exhaustive_short_sequences(self):
        # Independent full-matrix distance oracle on every binary sequence <= 3 words.
        sequences = [seq for n in range(4) for seq in itertools.product(['а', 'б'], repeat=n)]
        for ref in sequences:
            for hyp in sequences:
                matrix = [[0]*(len(hyp)+1) for _ in range(len(ref)+1)]
                for i in range(len(ref)+1): matrix[i][0] = i
                for j in range(len(hyp)+1): matrix[0][j] = j
                for i in range(1, len(ref)+1):
                    for j in range(1, len(hyp)+1):
                        matrix[i][j] = min(matrix[i-1][j]+1, matrix[i][j-1]+1,
                                           matrix[i-1][j-1]+(ref[i-1] != hyp[j-1]))
                result = word_errors(' '.join(ref), ' '.join(hyp))
                self.assertEqual(result['errors'], matrix[-1][-1])
                self.assertEqual(result['errors'], sum(result[k] for k in ['substitutions','deletions','insertions']))

    def test_cli_redacts_transcript_and_paths(self):
        script = Path(__file__).resolve().parents[1] / 'measure.py'
        with tempfile.TemporaryDirectory() as directory:
            ref, hyp = Path(directory)/'reference.txt', Path(directory)/'hypothesis.txt'
            ref.write_text('СЕКРЕТНОЕ_СЛОВО', encoding='utf-8')
            hyp.write_text('другое', encoding='utf-8')
            run = subprocess.run([sys.executable, str(script), '--reference', str(ref), '--hypothesis', str(hyp)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0)
            self.assertEqual(json.loads(run.stdout)['wer'], 1)
            self.assertNotIn('СЕКРЕТНОЕ', run.stdout + run.stderr)
            self.assertNotIn(directory, run.stdout + run.stderr)
            ref.unlink()
            run = subprocess.run([sys.executable, str(script), '--reference', str(ref), '--hypothesis', str(hyp)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 2)
            self.assertNotIn(directory, run.stdout + run.stderr)


if __name__ == '__main__':
    unittest.main()

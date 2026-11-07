import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

QUALITY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QUALITY))
from wer import counts, normalize


class WerTests(unittest.TestCase):
    # Synthetic text only.
    def test_normalize(self):
        self.assertEqual(normalize('  Ёлка,  ЗЕЛЁНАЯ!\n да — нет... '), 'елка зеленая да нет')

    def test_identical_after_normalization_is_zero(self):
        c = counts('Привет, как дела?', 'привет как дела')
        self.assertEqual((c['S'], c['D'], c['I'], c['N'], c['WER']), (0, 0, 0, 3, 0.0))

    def test_substitution_deletion_insertion(self):
        self.assertEqual(counts('мы не переносим встречу', 'мы не переносим встречи')['S'], 1)
        c = counts('мы не переносим встречу', 'мы переносим встречу')
        self.assertEqual((c['D'], c['WER']), (1, 0.25))
        c = counts('нужно 12 папок', 'нужно ровно 12 папок')
        self.assertEqual((c['I'], c['N'], c['WER']), (1, 3, 1 / 3))

    def test_mixed_errors(self):
        c = counts('раз два три четыре', 'раз три пять четыре шесть')
        self.assertEqual(c['S'] + c['D'] + c['I'], 3)
        self.assertEqual(c['N'], 4)

    def test_yo_is_e_on_both_sides(self):
        self.assertEqual(counts('ещё всё', 'еще все')['WER'], 0.0)
        self.assertEqual(counts('еще все', 'ещё всё')['WER'], 0.0)

    def test_digits_are_not_rewritten_but_can_be_dropped(self):
        c = counts('в 2015 году', 'в две тысячи пятнадцатом году')
        self.assertEqual((c['N'], c['S'] + c['D'] + c['I']), (3, 3))
        c = counts('в 2015 году', 'в две тысячи пятнадцатом году', drop_digits=True)
        self.assertEqual(c['N'], 2)  # "2015" dropped from the reference
        c = counts('нужно 12 папок', 'нужно 12 папок 3', drop_digits=True)
        self.assertEqual((c['N'], c['WER']), (2, 0.0))  # tokens with digits dropped on both sides

    def test_empty_reference(self):
        self.assertEqual(counts('', '')['WER'], 0.0)
        self.assertEqual(counts('', 'лишнее')['WER'], float('inf'))

    def test_cli_prints_counts_only_and_reads_export_json(self):
        with tempfile.TemporaryDirectory() as d:
            ref, hyp = Path(d, 'ref.txt'), Path(d, 'hyp.json')
            ref.write_text('Секретное слово один два', encoding='utf-8')
            hyp.write_text(json.dumps({'transcript': [{'speaker_id': 'speaker_1', 'start_ms': 0, 'end_ms': 1,
                                                       'text': 'секретное слово один'}]}), encoding='utf-8')
            out = subprocess.run([sys.executable, '-I', str(QUALITY / 'wer.py'), str(ref), str(hyp)],
                                 capture_output=True, text=True, check=True).stdout
        self.assertEqual(out.strip(), 'S=0 D=1 I=0 N=4 WER=0.2500')
        self.assertNotIn('секрет', out.lower())


if __name__ == '__main__':
    unittest.main()

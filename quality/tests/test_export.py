import copy
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_export import validate, reject_duplicates, reject_constant


class ExportTests(unittest.TestCase):
    def base(self):
        return {'transcript': [{'speaker_id': 'speaker_1', 'start_ms': 0,
                               'end_ms': 1000, 'text': 'Вымышленный пример.'}]}

    def test_valid(self):
        self.assertEqual(validate(self.base(), 1000), [])

    def test_empty(self):
        self.assertEqual(validate({'transcript': []}, 0), [])

    def test_bool_timestamp_rejected(self):
        data = self.base(); data['transcript'][0]['start_ms'] = False
        self.assertTrue(validate(data))

    def test_float_timestamp_rejected(self):
        data = self.base(); data['transcript'][0]['end_ms'] = 1000.0
        self.assertTrue(validate(data))

    def test_string_timestamp_rejected(self):
        data = self.base(); data['transcript'][0]['end_ms'] = '1000'
        self.assertTrue(validate(data))

    def test_bad_intervals(self):
        for start, end in [(-1, 2), (3, 3), (5, 1)]:
            with self.subTest(start=start, end=end):
                data=self.base(); data['transcript'][0].update(start_ms=start,end_ms=end)
                self.assertTrue(validate(data))

    def test_duration_bound(self):
        self.assertTrue(validate(self.base(), 999))

    def test_extra_fields_rejected(self):
        data=self.base(); data['transcript'][0]['rawText']='internal'
        self.assertTrue(validate(data))

    def test_missing_field(self):
        data=self.base(); del data['transcript'][0]['speaker_id']
        self.assertTrue(validate(data))

    def test_overlap_allowed(self):
        data=self.base(); second=copy.deepcopy(data['transcript'][0])
        second.update(speaker_id='speaker_2', start_ms=500, end_ms=1500)
        data['transcript'].append(second)
        self.assertEqual(validate(data, 1500), [])

    def test_returning_speaker_allowed(self):
        data=self.base()
        for speaker,start in [('speaker_2',1000),('speaker_1',2000)]:
            data['transcript'].append(dict(speaker_id=speaker,start_ms=start,end_ms=start+500,text='Пример'))
        self.assertEqual(validate(data, 2500), [])

    def test_unsorted(self):
        data=self.base(); data['transcript'].insert(0,dict(speaker_id='speaker_2',start_ms=500,end_ms=800,text='Пример'))
        self.assertTrue(validate(data))

    def test_invalid_root(self):
        for data in [[],None,{}, {'transcript':{}}, {'transcript':[], 'debug':True}]:
            self.assertTrue(validate(data))

    def test_invalid_speaker_or_text(self):
        for field, value in [('speaker_id','speaker_0'),('speaker_id',1),('text','  '),('text',None)]:
            data=self.base(); data['transcript'][0][field]=value
            self.assertTrue(validate(data))

    def test_duplicate_key(self):
        with self.assertRaises(ValueError):
            json.loads('{"transcript":[],"transcript":[]}',object_pairs_hook=reject_duplicates)

    def test_nan(self):
        with self.assertRaises(ValueError):
            json.loads('{"transcript":NaN}',parse_constant=reject_constant)

if __name__=='__main__':
    unittest.main()

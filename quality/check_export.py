"""Offline export shape checker; Python standard library only. Never prints text."""
import argparse
import json
import re
from pathlib import Path


def validate(payload, duration_ms=None):
    errors = []
    if duration_ms is not None and (type(duration_ms) is not int or duration_ms < 0):
        return ['duration_ms must be a nonnegative integer']
    if not isinstance(payload, dict) or set(payload) != {'transcript'}:
        return ['root must contain exactly transcript']
    rows = payload['transcript']
    if not isinstance(rows, list):
        return ['transcript must be an array']
    previous_start = -1
    for i, row in enumerate(rows):
        prefix = f'transcript[{i}]'
        if not isinstance(row, dict) or set(row) != {'speaker_id', 'start_ms', 'end_ms', 'text'}:
            errors.append(prefix + ': invalid fields')
            continue
        speaker = row['speaker_id']
        if not isinstance(speaker, str) or re.fullmatch(r'speaker_[1-9][0-9]*', speaker) is None:
            errors.append(prefix + ': invalid speaker_id')
        if not isinstance(row['text'], str) or not row['text'].strip():
            errors.append(prefix + ': text must be a nonempty string')
        start, end = row['start_ms'], row['end_ms']
        # bool is a subclass of int; JSON true is NOT an integer timestamp.
        if type(start) is not int or type(end) is not int:
            errors.append(prefix + ': timestamps must be integers')
            continue
        if not 0 <= start < end:
            errors.append(prefix + ': invalid interval')
        if duration_ms is not None and end > duration_ms:
            errors.append(prefix + ': interval exceeds duration')
        if start < previous_start:
            errors.append(prefix + ': rows must be ordered by start_ms')
        previous_start = start
    return errors


def reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate key')
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError('non-standard JSON number')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path)
    parser.add_argument('--duration-ms', type=int)
    args = parser.parse_args()
    try:
        payload = json.loads(args.file.read_text(encoding='utf-8'),
                             object_pairs_hook=reject_duplicates,
                             parse_constant=reject_constant)
    except (OSError, UnicodeError, ValueError):
        print('FAIL: unreadable or invalid strict UTF-8 JSON (content omitted)')
        return 2
    errors = validate(payload, args.duration_ms)
    if errors:
        for error in errors:
            print('FAIL:', error)
        return 1
    print('PASS: export structure; accuracy and speaker identity not evaluated')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

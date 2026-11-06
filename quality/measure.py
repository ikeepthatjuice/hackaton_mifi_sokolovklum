"""Local aggregate WER/RTF. Never prints transcripts, paths or exception text."""
import argparse
import json
import math
import unicodedata
from pathlib import Path

NORMALIZATION = 'NFC + casefold; Unicode letters/numbers; punctuation splits; yo distinct; digits unchanged; v1'


def words(text):
    """Keep ё distinct from е; don't convert written numbers into digits."""
    normalized = unicodedata.normalize('NFC', text).casefold()
    return ''.join(c if unicodedata.category(c)[0] in 'LNM' else ' '
                   for c in normalized).split()


def word_errors(reference, hypothesis):
    """Levenshtein counts, O(N*M) time/O(M) memory.

    Equal-cost alignments prefer substitution, then deletion, then insertion.
    The aggregate error count is invariant; S/D/I can depend on tie breaking.
    """
    ref, hyp = words(reference), words(hypothesis)
    previous = [(j, 0, 0, j) for j in range(len(hyp) + 1)]
    for i, token in enumerate(ref, 1):
        current = [(i, 0, i, 0)]
        for j, candidate in enumerate(hyp, 1):
            if token == candidate:
                current.append(previous[j - 1])
                continue
            diag, above, left = previous[j - 1], previous[j], current[-1]
            choices = [(diag[0]+1, diag[1]+1, diag[2], diag[3]),
                       (above[0]+1, above[1], above[2]+1, above[3]),
                       (left[0]+1, left[1], left[2], left[3]+1)]
            current.append(min(choices, key=lambda row: row[0]))
        previous = current
    errors, substitutions, deletions, insertions = previous[-1]
    return dict(reference_words=len(ref), hypothesis_words=len(hyp),
                substitutions=substitutions, deletions=deletions,
                insertions=insertions, errors=errors,
                wer=errors / len(ref) if ref else None)


def rtf(elapsed_ms, duration_ms):
    for value in (elapsed_ms, duration_ms):
        if type(value) not in (int, float) or not math.isfinite(value):
            raise ValueError('invalid timing')
    if elapsed_ms < 0 or duration_ms <= 0:
        raise ValueError('invalid timing')
    return elapsed_ms / duration_ms


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', required=True, type=Path)
    parser.add_argument('--hypothesis', required=True, type=Path)
    parser.add_argument('--elapsed-ms', type=float)
    parser.add_argument('--duration-ms', type=float)
    args = parser.parse_args()
    try:
        if (args.elapsed_ms is None) != (args.duration_ms is None):
            raise ValueError('timing pair required')
        ratio = rtf(args.elapsed_ms, args.duration_ms) if args.elapsed_ms is not None else None
        result = word_errors(args.reference.read_text(encoding='utf-8'),
                             args.hypothesis.read_text(encoding='utf-8'))
        result.update(normalization=NORMALIZATION, rtf=ratio)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    except (OSError, UnicodeError, ValueError):
        print('FAIL: invalid input or timings (content omitted)')
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

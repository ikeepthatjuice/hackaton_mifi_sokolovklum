"""WER = (S + D + I) / N over words, standard library only.

Normalization (same for reference and hypothesis): NFC, lower case, ё → е, punctuation → space,
whitespace collapsed. Numbers are not rewritten as words; --no-digits instead drops every token
that contains a digit from both sides before alignment.
Prints counts only: never reference or hypothesis text.

    python3 quality/wer.py [--no-digits] REF HYP   # each: plain text file, or export JSON {"transcript":[{"text":...}]}
"""
import json
import sys
import unicodedata


def normalize(text):
    text = unicodedata.normalize('NFC', text).lower().replace('ё', 'е')
    return ' '.join(''.join(c if unicodedata.category(c)[0] in 'LNM' else ' ' for c in text).split())


def counts(reference, hypothesis, drop_digits=False):
    """Word-level Levenshtein: dict(S, D, I, N, WER). Ties prefer substitution, then deletion."""
    ref, hyp = normalize(reference).split(), normalize(hypothesis).split()
    if drop_digits:
        ref, hyp = ([w for w in side if not any(c.isdigit() for c in w)] for side in (ref, hyp))
    # row[j] = (cost, S, D, I) for ref[:i] vs hyp[:j]
    row = [(j, 0, 0, j) for j in range(len(hyp) + 1)]
    for i, r in enumerate(ref, 1):
        new = [(i, 0, i, 0)]
        for j, h in enumerate(hyp, 1):
            if r == h:
                new.append(row[j - 1])
                continue
            sub, dele, ins = row[j - 1], row[j], new[j - 1]
            new.append(min([(sub[0] + 1, sub[1] + 1, sub[2], sub[3]),
                            (dele[0] + 1, dele[1], dele[2] + 1, dele[3]),
                            (ins[0] + 1, ins[1], ins[2], ins[3] + 1)], key=lambda t: t[0]))
        row = new
    _, s, d, i = row[-1]
    n = len(ref)
    return dict(S=s, D=d, I=i, N=n, WER=(s + d + i) / n if n else (0.0 if not hyp else float('inf')))


def read_text(path):
    with open(path, encoding='utf-8') as f:
        data = f.read()
    if path.endswith('.json'):
        return ' '.join(row['text'] for row in json.loads(data)['transcript'])
    return data


def main(argv):
    drop_digits = '--no-digits' in argv
    paths = [a for a in argv[1:] if a != '--no-digits']
    if len(paths) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    c = counts(read_text(paths[0]), read_text(paths[1]), drop_digits)
    print(f"S={c['S']} D={c['D']} I={c['I']} N={c['N']} WER={c['WER']:.4f}")
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))

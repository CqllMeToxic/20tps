"""makes the built-in 20tps charts, or maps, or whatever the heck you want to call them.

each song is just a melody: a list of (note, beats) pairs, where `note` is a note
block pitch (0 = F#3 ... 24 = F#5) and `beats` is how long until the next note.
the targets get laid out so higher notes sit higher on the playfield, and they
sweep left and right with bigger jumps for longer gaps.

W jumpslop

"""
import json
import math
import os
import random

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "twentytps", "charts")

# note block pitch numbers
Fs3, G3, Gs3, A3, As3, B3, C4, Cs4, D4, Ds4, E4, F4 = range(12)
Fs4, G4, Gs4, A4, As4, B4, C5, Cs5, D5, Ds5, E5, F5, Fs5 = range(12, 25)

SONGS = {
    "first_light": {
        "title": "First Light",
        "artist": "20tps",
        "bpm": 120,
        "instrument": "pling",
        "difficulty": 1,
        "approachMs": 1100,
        "seed": 3,
        "melody": [
            (C4, 1), (E4, 1), (G4, 1), (C5, 1),
            (B4, .5), (A4, .5), (G4, 1), (E4, 1), (G4, 1),
            (A4, 1), (F4, 1), (A4, 1), (C5, 1),
            (D5, 1), (C5, 1), (B4, 2),
            (C5, .5), (B4, .5), (A4, 1), (G4, .5), (F4, .5), (E4, 1),
            (F4, 1), (A4, 1), (D5, 1), (C5, 1),
            (B4, .5), (C5, .5), (D5, 1), (E5, 1), (D5, .5), (B4, .5),
            (C5, 2), (G4, 1), (C4, 1),
        ],
    },
    "night_shift": {
        "title": "Night Shift",
        "artist": "20tps",
        "bpm": 150,
        "instrument": "bell",
        "difficulty": 3,
        "approachMs": 800,
        "seed": 11,
        "melody": [
            (A4, .5), (C5, .5), (E5, .5), (C5, .5), (A4, .5), (E4, .5), (A4, 1),
            (G4, .5), (B4, .5), (D5, .5), (B4, .5), (G4, .5), (D4, .5), (G4, 1),
            (F4, .5), (A4, .5), (C5, .5), (A4, .5), (F4, .5), (C4, .5), (F4, 1),
            (E4, .5), (Gs4, .5), (B4, .5), (E5, 1), (D5, .5), (C5, .5), (B4, .5),
            (A4, 1), (E5, .5), (D5, .5), (C5, .5), (B4, .5), (A4, 1),
            (G4, 1), (D5, .5), (C5, .5), (B4, .5), (A4, .5), (G4, 1),
            (F4, .5), (A4, .5), (C5, .5), (F5, .5), (E5, .5), (C5, .5), (A4, 1),
            (B4, .5), (Gs4, .5), (E4, .5), (Gs4, .5), (B4, 1), (E5, 1),
            (A4, 2),
        ],
    },
}

W, H, MARGIN = 512, 384, 48


def layout(song):
    rng = random.Random(song["seed"])
    melody = song["melody"]
    pitches = [n for n, _ in melody]
    lo, hi = min(pitches), max(pitches)
    notes, beat, x, direction = [], 0.0, W / 2, 1
    prev_gap = 1.0
    for i, (n, gap) in enumerate(melody):
        if i > 0:
            step = max(50, min(190, prev_gap * 130)) * rng.uniform(0.85, 1.15)
            x += direction * step
            if x > W - MARGIN or x < MARGIN:
                direction = -direction
                x = max(MARGIN, min(W - MARGIN, x + 2 * direction * step))
            if rng.random() < 0.15:
                direction = -direction
        t = (n - lo) / max(1, hi - lo)
        y = (H - MARGIN) - t * (H - 2 * MARGIN) + rng.uniform(-14, 14)
        notes.append({"beat": round(beat, 3), "x": round(x), "y": round(max(MARGIN, min(H - MARGIN, y))), "n": n})
        beat += gap
        prev_gap = gap
    return notes


def main():
    os.makedirs(OUT, exist_ok=True)
    for key, song in SONGS.items():
        chart = {k: song[k] for k in ("title", "artist", "bpm", "instrument", "difficulty", "approachMs")}
        chart["offsetMs"] = 0
        chart["notes"] = layout(song)
        with open(os.path.join(OUT, key + ".json"), "w") as f:
            json.dump(chart, f, indent=1)
        print(f"{key}: {len(chart['notes'])} notes")


if __name__ == "__main__":
    main()

"""Generate the notification sounds for the web client, the iOS app and the Android app.

    python3 scripts/gen_notification_sounds.py

writes web/public/sounds/<id>.wav, ios/shroud/Resources/Sounds/<id>.wav and
android/app/src/main/res/raw/<id>.wav: short tones made here from sine partials (no recordings,
so no licences), 24 kHz mono 16-bit PCM, the same bytes in all three. The clients list the same
ids (web/src/notifications/sounds.ts, ios/shroud/Services/Notifications/NotificationSound.swift,
android/.../core/notifications/NotificationSound.kt); the server only passes an id through to
APNs as `<id>.wav`, which is why iOS keeps them in the app bundle. Android plays them in the app
and as the sound of its notification channels. WAV is a format iOS plays for notifications,
Android plays everywhere and every browser plays in <audio>.
"""
import math, pathlib, struct, wave

ROOT = pathlib.Path(__file__).resolve().parents[1]
RATE = 24_000
PEAK = 0.72


def envelope(t, attack, decay):
    """Fast rise, exponential fall."""
    if t < attack:
        return t / attack
    return math.exp(-(t - attack) / decay)


def tone(freq, start, length, partials, attack=0.004, decay=0.18):
    """[(sample index, value)] for one struck note built from (ratio, gain, decay scale) partials."""
    out = []
    first = int(start * RATE)
    for i in range(int(length * RATE)):
        t = i / RATE
        value = 0.0
        for ratio, gain, decay_scale in partials:
            value += gain * math.sin(2 * math.pi * freq * ratio * t) * envelope(t, attack, decay * decay_scale)
        out.append((first + i, value))
    return out


def render(notes, length):
    samples = [0.0] * int(length * RATE)
    for note in notes:
        for index, value in note:
            if index < len(samples):
                samples[index] += value
    # Short fades so the file never starts or stops on a click.
    fade = int(0.004 * RATE)
    for i in range(fade):
        samples[i] *= i / fade
        samples[-1 - i] *= i / fade
    top = max(abs(s) for s in samples) or 1.0
    return [s / top * PEAK for s in samples]


SOFT = [(1, 1.0, 1.0), (2, 0.18, 0.5), (3, 0.06, 0.35)]
BELL = [(1, 1.0, 1.0), (2.0, 0.5, 0.7), (2.76, 0.35, 0.55), (5.4, 0.18, 0.35), (8.93, 0.08, 0.25)]
GLASS = [(1, 1.0, 1.0), (2.005, 0.3, 0.8), (3.99, 0.12, 0.5)]


def pop():
    """A bubble: a sine whose pitch jumps up while it dies away, twice."""
    notes = []
    for start, base in ((0.0, 420.0), (0.075, 560.0)):
        first = int(start * RATE)
        phase = 0.0
        note = []
        for i in range(int(0.07 * RATE)):
            t = i / RATE
            freq = base * (1 + 1.3 * (1 - math.exp(-t / 0.012)))
            phase += 2 * math.pi * freq / RATE
            note.append((first + i, math.sin(phase) * envelope(t, 0.002, 0.02)))
        notes.append(note)
    return render(notes, 0.2)


def pulse():
    """Two soft rounded boops, a third apart."""
    notes = []
    for start, freq in ((0.0, 659.25), (0.13, 830.61)):
        partials = [(1, 1.0, 1.0), (3, 0.11, 0.6), (5, 0.04, 0.4)]
        notes.append(tone(freq, start, 0.2, partials, attack=0.008, decay=0.06))
    return render(notes, 0.36)


SOUNDS = {
    # Two gentle rising notes: the web client's default.
    "note": lambda: render(
        [tone(1318.51, 0.0, 0.4, SOFT, decay=0.12), tone(1760.0, 0.11, 0.45, SOFT, decay=0.16)], 0.58
    ),
    "chime": lambda: render([tone(880.0, 0.0, 1.3, BELL, decay=0.45)], 1.3),
    "glass": lambda: render(
        [tone(2093.0, 0.0, 1.0, GLASS, attack=0.01, decay=0.3), tone(3136.0, 0.09, 0.8, GLASS, attack=0.01, decay=0.22)],
        1.0,
    ),
    "pop": pop,
    "pulse": pulse,
}


# Every client gets the same file. Android resource names must be lower-case letters, digits and
# "_", which every id here already is.
OUTPUTS = ("web/public/sounds", "ios/shroud/Resources/Sounds", "android/app/src/main/res/raw")


def write(path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples))


def main():
    for name, make in SOUNDS.items():
        samples = make()
        for folder in OUTPUTS:
            write(ROOT / folder / f"{name}.wav", samples)
        print(f"{name}: {len(samples) / RATE:.2f}s")


if __name__ == "__main__":
    main()

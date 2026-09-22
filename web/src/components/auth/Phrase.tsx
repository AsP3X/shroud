import { useEffect, useRef, type ClipboardEvent, type KeyboardEvent } from "react";
import { WORD_COUNT } from "../../crypto/bip39";
import { CopyButton } from "../CopyButton";

function splitWords(text: string): string[] {
  return text
    .trim()
    .toLowerCase()
    .split(/[^a-z]+/)
    .filter(Boolean);
}

export function PhraseDisplay({ words }: { words: string[] }) {
  return (
    <div className="phrase">
      <ol className="phrase-grid">
        {words.map((word, i) => (
          <li key={i} className="phrase-cell">
            <span className="phrase-n">{i + 1}</span>
            <span className="phrase-word">{word}</span>
          </li>
        ))}
      </ol>
      <div className="phrase-actions">
        <CopyButton value={words.join(" ")} label="encryption phrase" />
      </div>
    </div>
  );
}

export function PhraseEntry({
  words,
  onChange,
  autoFocus = false,
}: {
  words: string[];
  onChange: (next: string[]) => void;
  autoFocus?: boolean;
}) {
  const cells = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    if (autoFocus) cells.current[0]?.focus();
  }, [autoFocus]);

  function focusCell(index: number) {
    cells.current[Math.max(0, Math.min(WORD_COUNT - 1, index))]?.focus();
  }

  function padded(next: string[]): string[] {
    return Array.from({ length: WORD_COUNT }, (_, i) => next[i] ?? "");
  }

  function setWord(index: number, value: string) {
    const next = padded(words);
    next[index] = value.toLowerCase().replace(/[^a-z]/g, "");
    onChange(next);
  }

  /* Pasting the whole phrase into any cell fills the rest — the way people
     actually move a 12-word phrase between devices. */
  function onPaste(index: number, event: ClipboardEvent<HTMLInputElement>) {
    const parts = splitWords(event.clipboardData.getData("text"));
    if (parts.length < 2) return;
    event.preventDefault();
    const next = padded(words);
    parts.slice(0, WORD_COUNT - index).forEach((word, offset) => {
      next[index + offset] = word;
    });
    onChange(next);
    focusCell(Math.min(WORD_COUNT - 1, index + parts.length));
  }

  function onKeyDown(index: number, event: KeyboardEvent<HTMLInputElement>) {
    const el = event.currentTarget;
    if (event.key === " " || event.key === "Enter") {
      if (words[index]) {
        event.preventDefault();
        focusCell(index + 1);
      }
      return;
    }
    if (event.key === "Backspace" && !el.value && index > 0) {
      event.preventDefault();
      focusCell(index - 1);
      return;
    }
    if (event.key === "ArrowRight" && el.selectionStart === el.value.length) focusCell(index + 1);
    if (event.key === "ArrowLeft" && el.selectionStart === 0) focusCell(index - 1);
  }

  const filled = words.filter(Boolean).length;

  return (
    <div className="phrase">
      <ol className="phrase-grid">
        {Array.from({ length: WORD_COUNT }, (_, i) => {
          const word = words[i] ?? "";
          return (
            <li key={i} className="phrase-cell">
              <span className="phrase-n">{i + 1}</span>
              <input
                ref={(node) => {
                  cells.current[i] = node;
                }}
                className="phrase-input"
                type="text"
                inputMode="text"
                maxLength={8}
                value={word}
                onChange={(event) => setWord(i, event.target.value)}
                onPaste={(event) => onPaste(i, event)}
                onKeyDown={(event) => onKeyDown(i, event)}
                aria-label={`Word ${i + 1}`}
                autoCapitalize="none"
                autoCorrect="off"
                autoComplete="off"
                spellCheck={false}
                enterKeyHint={i === WORD_COUNT - 1 ? "done" : "next"}
              />
            </li>
          );
        })}
      </ol>
      <p className="phrase-status">
        {filled} of {WORD_COUNT} words. Nothing is checked until you continue.
      </p>
    </div>
  );
}

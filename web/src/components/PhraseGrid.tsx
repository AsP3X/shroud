export function PhraseGrid({
  words,
  editable = false,
  onChange,
}: {
  words: string[];
  editable?: boolean;
  onChange?: (index: number, value: string) => void;
}) {
  return (
    <ol className="phrase-grid">
      {Array.from({ length: 12 }, (_, i) => (
        <li key={i} className="phrase-cell">
          <span className="phrase-n">{i + 1}</span>
          {editable ? (
            <input
              className="phrase-input"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              value={words[i] ?? ""}
              onChange={(e) => onChange?.(i, e.target.value)}
            />
          ) : (
            <span className="phrase-word">{words[i] || "••••"}</span>
          )}
        </li>
      ))}
    </ol>
  );
}

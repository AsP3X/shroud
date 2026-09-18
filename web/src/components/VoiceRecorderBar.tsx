import { ArrowUp, Trash2 } from "lucide-react";
import { formatRecordingTime } from "../crypto/mediaPayload";

export function VoiceLockedBar({
  elapsed,
  levels,
  onDiscard,
  onSend,
  sending,
}: {
  elapsed: number;
  levels: number[];
  onDiscard: () => void;
  onSend: () => void;
  sending?: boolean;
}) {
  const slots = 44;
  const bars = Array.from({ length: slots }, (_, i) => {
    const sample = levels[levels.length - slots + i];
    return typeof sample === "number" ? sample : 0.08;
  });
  return (
    <div className="voice-locked" role="group" aria-label="Recording voice message">
      <button
        type="button"
        className="voice-locked-discard"
        onClick={onDiscard}
        disabled={sending}
        aria-label="Discard recording"
      >
        <Trash2 size={18} />
      </button>
      <div className="voice-locked-capsule">
        <span className="voice-rec-dot" aria-hidden="true" />
        <span className="voice-rec-time">{formatRecordingTime(elapsed)}</span>
        <div className="voice-live-wave" aria-hidden="true">
          {bars.map((sample, index) => (
            <span
              key={index}
              className="voice-bar"
              style={{
                height: `${Math.max(14, Math.min(1, sample) * 100)}%`,
                ["--played" as string]: "1",
              }}
            />
          ))}
        </div>
      </div>
      <button
        type="button"
        className="send"
        onClick={onSend}
        disabled={sending}
        aria-label="Send voice message"
      >
        <ArrowUp size={18} strokeWidth={2.6} />
      </button>
    </div>
  );
}

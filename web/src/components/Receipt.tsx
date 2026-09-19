import { Check, CheckCheck, Clock } from "lucide-react";
import type { ChatMessage } from "../messaging";

/** Delivery state of one of our messages: sending, sent, delivered, read. */
export function Receipt({ message }: { message: ChatMessage }) {
  if (message.pending) return <Clock size={13} aria-label="Sending" />;
  if (message.read) return <CheckCheck size={14} className="receipt-read" aria-label="Read" />;
  if (message.delivered) return <CheckCheck size={14} aria-label="Delivered" />;
  return <Check size={14} aria-label="Sent" />;
}

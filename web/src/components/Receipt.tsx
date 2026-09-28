import { Check, CheckCheck, Clock } from "lucide-react";
import type { ChatMessage } from "../messaging";
import { usePrivacySettings } from "../privacy";

/**
 * Delivery state of one of our messages: sending, sent, delivered, read. With read receipts
 * off, a read already loaded shows as delivered (the server stops reporting new ones).
 */
export function Receipt({ message }: { message: ChatMessage }) {
  const showsReads = usePrivacySettings()?.send_read_receipts !== false;
  if (message.pending) return <Clock size={13} aria-label="Sending" />;
  if (message.read && showsReads) return <CheckCheck size={14} className="receipt-read" aria-label="Read" />;
  if (message.delivered) return <CheckCheck size={14} aria-label="Delivered" />;
  return <Check size={14} aria-label="Sent" />;
}

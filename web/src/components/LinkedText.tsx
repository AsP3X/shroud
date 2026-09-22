import { useMemo } from "react";
import { splitLinks } from "../links";
import { Highlight } from "./Highlight";

/**
 * Message text with its links made clickable, search hits still marked.
 *
 * Telegram's rule: a link takes the link colour and is underlined only where that colour is the
 * text's own (outgoing bubbles) — see `.msg-link` in index.css. Web links open in a new tab
 * without a referrer; `mailto:` opens the mail app. The detector (links.ts) never yields any
 * other scheme, so nothing here can run script.
 */
export function LinkedText({ text, query }: { text: string; query: string }) {
  const parts = useMemo(() => splitLinks(text), [text]);
  return (
    <>
      {parts.map((part, index) => {
        if (!part.link) return <Highlight key={index} text={part.text} query={query} />;
        const mail = part.link.isEmail;
        return (
          <a
            key={index}
            className="msg-link"
            href={part.link.url}
            data-link={part.link.url}
            target={mail ? undefined : "_blank"}
            rel="noopener noreferrer nofollow"
            onClick={(event) => event.stopPropagation()}
          >
            <Highlight text={part.text} query={query} />
          </a>
        );
      })}
    </>
  );
}

import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ComponentType,
  type KeyboardEvent,
  type ReactNode,
} from "react";

/** A point in viewport coordinates: the pointer, or an edge of the control that opened it. */
export type MenuAnchor = { x: number; y: number };

export type MenuItem<Id extends string = string> = {
  id: Id;
  label: string;
  Icon: ComponentType<{ size?: number; "aria-hidden"?: boolean | "true" }>;
  /** Destructive actions render red. */
  danger?: boolean;
  /** Draw a hairline above this item (e.g. to set a destructive action apart). */
  separatorBefore?: boolean;
};

/** Keeps the menu this far from every viewport edge. */
const EDGE = 8;

/**
 * The long-press that opens a menu is still held. Its release would otherwise click
 * whatever item appeared under the finger. Call this synchronously, before React paints.
 */
export function suppressClickAfterLongPress(): void {
  const onClick = (event: MouseEvent) => {
    event.preventDefault();
    event.stopPropagation();
    window.removeEventListener("click", onClick, true);
  };
  window.addEventListener("click", onClick, true);
  const drop = () => {
    // The click is dispatched after pointerup, still before a 0ms timer.
    window.setTimeout(() => window.removeEventListener("click", onClick, true), 0);
  };
  window.addEventListener("pointerup", drop, { capture: true, once: true });
  window.addEventListener("pointercancel", drop, { capture: true, once: true });
}

/**
 * The web client's one popup menu: message actions, the account menu — anything that opens
 * at a point and offers a short list of actions.
 *
 * Opens at `anchor` and flips up or left when it would run off screen, the way native menus
 * do. Focus moves into it so the arrow keys work; Escape, a click outside, scrolling, resizing
 * or leaving the window close it without acting. Escape is taken in the capture phase with
 * `stopImmediatePropagation`, so it never also reaches a screen-level Escape handler (cancel a
 * reply, leave a settings page).
 */
export function ContextMenu<Id extends string>({
  anchor,
  items,
  label,
  header,
  onSelect,
  onClose,
  trigger,
  settle = false,
}: {
  anchor: MenuAnchor;
  items: MenuItem<Id>[];
  /** Accessible name of the menu. */
  label: string;
  /** Optional non-interactive block above the items (the account menu's name and handle). */
  header?: ReactNode;
  onSelect: (id: Id) => void;
  onClose: () => void;
  /**
   * The control that opened the menu, if any. Focus goes back to it when the menu closes, and
   * pressing it again is left to its own click (a toggle) rather than counted as "outside".
   */
  trigger?: HTMLElement | null;
  /** Opened by a finger that is still down. Ignore that gesture, then accept taps. */
  settle?: boolean;
}) {
  const menu = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<{ left: number; top: number; origin: string } | null>(
    null,
  );
  const [live, setLive] = useState(!settle);
  /* Content that grows after opening (the reaction row expanding) re-fits the menu on screen. */
  const [grown, setGrown] = useState(0);
  useEffect(() => {
    const node = menu.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    let first = true;
    const observer = new ResizeObserver(() => {
      if (first) {
        first = false;
        return;
      }
      setGrown((n) => n + 1);
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  /* Read through refs: callers pass inline closures, and re-subscribing the window listeners
     on every render would drop events mid-gesture. */
  const close = useRef(onClose);
  close.current = onClose;
  const opener = useRef(trigger);
  opener.current = trigger;

  /* Measure once mounted (invisible until then), then clamp into the viewport. The layout size,
     not getBoundingClientRect: that includes the scale-in animation's first frame and comes up
     4% short, which left a flipped menu hanging past its anchor. */
  useLayoutEffect(() => {
    const node = menu.current;
    if (!node) return;
    const { offsetWidth: width, offsetHeight: height } = node;
    const maxLeft = Math.max(EDGE, window.innerWidth - width - EDGE);
    const maxTop = Math.max(EDGE, window.innerHeight - height - EDGE);
    const fitsRight = anchor.x + width + EDGE <= window.innerWidth;
    const fitsBelow = anchor.y + height + EDGE <= window.innerHeight;
    const left = Math.min(maxLeft, Math.max(EDGE, fitsRight ? anchor.x : anchor.x - width));
    const top = Math.min(maxTop, Math.max(EDGE, fitsBelow ? anchor.y : anchor.y - height));
    /* Grow out of the corner at the anchor, the way native menus open. */
    const origin = `${fitsRight ? "left" : "right"} ${fitsBelow ? "top" : "bottom"}`;
    setPosition({ left, top, origin });
  }, [anchor.x, anchor.y, items.length, grown]);

  /* The finger that opened the menu is still down. Hits pass through until it lifts. */
  useEffect(() => {
    if (!settle) {
      setLive(true);
      return;
    }
    setLive(false);
    const enable = () => setLive(true);
    window.addEventListener("pointerup", enable, { capture: true, once: true });
    window.addEventListener("pointercancel", enable, { capture: true, once: true });
    return () => {
      window.removeEventListener("pointerup", enable, true);
      window.removeEventListener("pointercancel", enable, true);
    };
  }, [settle]);

  /* Focus has to wait until the menu is placed: a `visibility: hidden` element can't take it.
     A long-press also waits until the finger lifts, so focusing under it can't scroll the thread. */
  const placed = position !== null;
  useEffect(() => {
    if (placed && live) {
      // The first action, not a control in the header: Enter must not pick a reaction.
      const first =
        menu.current?.querySelector<HTMLButtonElement>('[role="menuitem"]') ??
        menu.current?.querySelector<HTMLButtonElement>("button");
      first?.focus({ preventScroll: true });
    }
  }, [placed, live]);

  useEffect(() => {
    const openedAt = performance.now();
    const dismiss = (event?: Event) => {
      // Focusing the first item can nudge a scroller in the same turn the menu opens.
      if (event?.type === "scroll" && performance.now() - openedAt < 350) return;
      // Scrolling inside the menu (the full reaction set) is using it, not leaving it.
      if (event?.type === "scroll" && event.target instanceof Node && menu.current?.contains(event.target)) {
        return;
      }
      close.current();
    };
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (menu.current?.contains(target) || opener.current?.contains(target)) return;
      close.current();
    };
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopImmediatePropagation();
      opener.current?.focus({ preventScroll: true });
      close.current();
    };
    window.addEventListener("pointerdown", onPointerDown, true);
    window.addEventListener("keydown", onKeyDown, true);
    window.addEventListener("resize", dismiss);
    window.addEventListener("blur", dismiss);
    document.addEventListener("scroll", dismiss, true);
    return () => {
      window.removeEventListener("pointerdown", onPointerDown, true);
      window.removeEventListener("keydown", onKeyDown, true);
      window.removeEventListener("resize", dismiss);
      window.removeEventListener("blur", dismiss);
      document.removeEventListener("scroll", dismiss, true);
    };
  }, []);

  /* Arrow keys walk the items and wrap; Home/End jump; Tab leaves the menu. A header with
     controls (the reaction row) is one stop for Up/Down, walked with Left/Right. */
  function onMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const root = menu.current;
    if (!root) return;
    const active = document.activeElement as HTMLButtonElement | null;
    const headerButtons = [...root.querySelectorAll<HTMLButtonElement>(".ctx-menu-header button")];
    const inHeader = active != null && headerButtons.includes(active);
    if (inHeader && (event.key === "ArrowRight" || event.key === "ArrowLeft")) {
      const at = headerButtons.indexOf(active);
      const step = event.key === "ArrowRight" ? 1 : -1;
      event.preventDefault();
      headerButtons[(at + step + headerButtons.length) % headerButtons.length]?.focus();
      return;
    }
    const items = [...root.querySelectorAll<HTMLButtonElement>(".ctx-menu-item")];
    const headerStop =
      headerButtons.find((button) => button.getAttribute("aria-checked") === "true") ?? headerButtons[0];
    const stops = [...(headerStop ? [inHeader ? active : headerStop] : []), ...items];
    if (stops.length === 0) return;
    const index = inHeader ? 0 : stops.indexOf(active as HTMLButtonElement);
    let next = -1;
    if (event.key === "ArrowDown") next = (index + 1) % stops.length;
    else if (event.key === "ArrowUp") next = (index - 1 + stops.length) % stops.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = stops.length - 1;
    else if (event.key === "Tab") {
      close.current();
      return;
    }
    if (next < 0) return;
    event.preventDefault();
    stops[next]?.focus();
  }

  return (
    <div
      ref={menu}
      className="ctx-menu"
      role="menu"
      aria-label={label}
      style={{
        left: position?.left ?? anchor.x,
        top: position?.top ?? anchor.y,
        transformOrigin: position?.origin,
        visibility: position ? "visible" : "hidden",
        pointerEvents: live ? "auto" : "none",
      }}
      onKeyDown={onMenuKeyDown}
      onContextMenu={(event) => event.preventDefault()}
    >
      {header ? (
        <div className="ctx-menu-header" role="none">
          {header}
        </div>
      ) : null}
      {items.map(({ id, label: text, Icon, danger, separatorBefore }) => (
        <div key={id} role="none">
          {separatorBefore ? <hr className="ctx-menu-sep" /> : null}
          <button
            type="button"
            role="menuitem"
            className={danger ? "ctx-menu-item danger" : "ctx-menu-item"}
            onClick={() => {
              opener.current?.focus({ preventScroll: true });
              onSelect(id);
            }}
          >
            <Icon size={16} aria-hidden="true" />
            {text}
          </button>
        </div>
      ))}
    </div>
  );
}

import { createElement, useCallback, type MouseEvent } from "react";
import { flushSync } from "react-dom";
import { Link, useNavigate, type LinkProps, type NavigateOptions, type To } from "react-router-dom";

/*
 * The way from the welcome page to log in or sign up, and back. The route change runs as a
 * view transition: the brand mark travels from the welcome page's top bar to the sign-in page's
 * brand column and grows into its place, the brand column slides in from the left, the card
 * rises from below, and the welcome page fades out behind them (the `auth-*-in/out` and
 * `brand-mark` rules in index.css). Back runs it in reverse. A browser without view transitions,
 * or a visitor who asked for less motion, simply gets the next page.
 *
 * `direction` tells the stylesheet which way: forward (into a sign-in page) or back (out of one).
 */

export type Direction = "forward" | "back";

function motionWanted(): boolean {
  return !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** Applies `update` as a view transition where there is one, and plainly where there is not. */
export function transition(update: () => void, direction: Direction = "forward"): void {
  const root = document.documentElement;
  if (typeof document.startViewTransition !== "function" || !motionWanted()) {
    update();
    return;
  }
  root.dataset.landingNav = direction;
  const running = document.startViewTransition(() => {
    // The route (or step) has to be in the DOM before the browser takes its second picture.
    flushSync(update);
  });
  void running.finished.finally(() => {
    delete root.dataset.landingNav;
  });
}

/** `navigate` between the welcome page and the sign-in pages, as a view transition. */
export function useLandingNavigate() {
  const navigate = useNavigate();
  return useCallback(
    (to: To, options: NavigateOptions & { direction?: Direction } = {}) => {
      const { direction, ...rest } = options;
      transition(() => void navigate(to, rest), direction);
    },
    [navigate],
  );
}

type LandingLinkProps = LinkProps & { direction?: Direction };

/** A `Link` between the welcome page and the sign-in pages that moves with the transition. Modified clicks keep the browser's own behaviour. */
export function LandingLink({ direction, onClick, ...props }: LandingLinkProps) {
  const go = useLandingNavigate();
  const handle = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event);
    if (event.defaultPrevented || event.button !== 0) return;
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (props.target && props.target !== "_self") return;
    event.preventDefault();
    go(props.to, { direction, state: props.state, replace: props.replace });
  };
  return createElement(Link, { ...props, onClick: handle });
}

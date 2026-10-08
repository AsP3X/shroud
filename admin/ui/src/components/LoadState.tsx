import { CircleAlert, RefreshCw } from "lucide-react";
import { ApiError } from "../api/client";
import { Button, Card, CardBody, CardHead } from "./ui";

/** The frames' "Couldn't load" card: what failed, in the contract's words, and a retry. */
export function LoadError({ error, onRetry, what = "This page" }: { error: Error; onRetry: () => void; what?: string }) {
  let title = `${what} couldn't load`;
  let body = error.message;
  if (error instanceof ApiError && error.code === "UPSTREAM") {
    title = error.upstream === "postgres" ? "The console can't reach its database" : "The console can't reach the API";
  } else if (error instanceof ApiError && error.code === "FORBIDDEN") {
    title = "Your role can't see this";
  } else if (!(error instanceof ApiError)) {
    body = "Check your connection, then try again.";
  }
  return (
    <Card>
      <CardHead title={title} sub={body}>
        <CircleAlert aria-hidden="true" style={{ color: "var(--danger)", width: 18, height: 18 }} />
      </CardHead>
      <CardBody>
        <div>
          <Button icon={RefreshCw} onClick={onRetry}>
            Try again
          </Button>
        </div>
      </CardBody>
    </Card>
  );
}

export function Loading({ what = "Loading" }: { what?: string }) {
  return (
    <div className="meta" role="status" aria-live="polite">
      {what}…
    </div>
  );
}

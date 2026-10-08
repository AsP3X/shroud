import type { ReactNode } from "react";
import type { PageData } from "../api/usePageData";
import { LoadError, Loading } from "./LoadState";
import { PageHeader } from "./ui";

/** The loading and "Couldn't load" states every read-only page shares; `children` renders the data. */
export function PageFrame<T>({
  title,
  page,
  what,
  children,
}: {
  title: string;
  page: PageData<T>;
  what?: string;
  children: (data: T) => ReactNode;
}) {
  if (page.error) {
    return (
      <>
        <PageHeader title={title} />
        <LoadError error={page.error} onRetry={page.reload} what={what ?? title} />
      </>
    );
  }
  if (!page.data) {
    return (
      <>
        <PageHeader title={title} />
        <Loading />
      </>
    );
  }
  return <>{children(page.data)}</>;
}

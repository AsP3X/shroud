import { Construction } from "lucide-react";
import { Notice, PageHeader } from "../components/ui";

/** A page whose route exists but whose content is a later task (docs/admin-plan.md §5). */
export function Stub({ title, task }: { title: string; task: string }) {
  return (
    <>
      <PageHeader title={title} />
      <Notice icon={Construction}>This page is built in task {task}.</Notice>
    </>
  );
}

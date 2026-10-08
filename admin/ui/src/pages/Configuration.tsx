import { Lock } from "lucide-react";
import { usePageData } from "../api/usePageData";
import type { ConfigurationGroup } from "../api/types";
import { PageFrame } from "../components/PageFrame";
import { Card, CardHead, Chip, Notice, PageHeader } from "../components/ui";

/** Frame "Configuration": the API's variables as the console's own environment carries them;
 *  secrets only as set or not set (§3.4). */
export function Configuration() {
  const page = usePageData<ConfigurationGroup[]>("/configuration");
  return (
    <PageFrame title="Configuration" page={page}>
      {(groups) => {
        const half = Math.ceil(groups.length / 2);
        const columns = [groups.slice(0, half), groups.slice(half)];
        return (
          <>
            <PageHeader title="Configuration" meta="Read from the environment when the console started" />
            <Notice>
              Read-only. To change a value, update the deployment's environment and run ./deploy.sh, which restarts the API. Secrets are never
              shown, only whether they're set.
            </Notice>
            <div className="columns">
              {columns.map((column, index) => (
                <div className="column" key={index}>
                  {column.map((group) => (
                    <Card key={group.group}>
                      <CardHead title={group.group} />
                      <div className="kv">
                        {group.variables.map((variable) => (
                          <div className="kv__row" key={variable.name} style={{ minHeight: 38, padding: "9px 0" }}>
                            <span className="mono small text-secondary" style={{ flex: 1 }}>
                              {variable.name}
                            </span>
                            {variable.secret ? (
                              <Chip tone={variable.set ? undefined : "warn"}>
                                <Lock size={11} aria-hidden="true" style={{ marginRight: 5 }} />
                                {variable.set ? "Set" : "Not set"}
                              </Chip>
                            ) : (
                              <span className={variable.value === null ? "mono small text-tertiary" : "mono small text-primary"}>{variable.value ?? "Not set"}</span>
                            )}
                          </div>
                        ))}
                      </div>
                    </Card>
                  ))}
                </div>
              ))}
            </div>
          </>
        );
      }}
    </PageFrame>
  );
}

import { PageHeader } from "@/components/PageHeader";
import { ToolForm } from "@/components/forms/ToolForm";

export default function NewToolPage() {
  return (
    <>
      <PageHeader title="New Tool Documentation" description="Names, measurements, maker and use." />
      <ToolForm />
    </>
  );
}

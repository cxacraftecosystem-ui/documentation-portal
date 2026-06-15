import { PageHeader } from "@/components/PageHeader";
import { ToolForm } from "@/components/forms/ToolForm";

export default function NewToolPage() {
  return (
    <>
      <PageHeader title="New Tool Documentation" description="Record tool names, process context, measurements, maker, tradition and improvement notes." />
      <ToolForm />
    </>
  );
}

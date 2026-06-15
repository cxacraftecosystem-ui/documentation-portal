import { ArtisanForm } from "@/components/forms/ArtisanForm";
import { PageHeader } from "@/components/PageHeader";

export default function NewArtisanPage() {
  return (
    <>
      <PageHeader title="New Artisan" description="Capture artisan identity, craft linkage, place, notes and optional GPS details." />
      <ArtisanForm />
    </>
  );
}

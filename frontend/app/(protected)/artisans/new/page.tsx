import { ArtisanForm } from "@/components/forms/ArtisanForm";
import { PageHeader } from "@/components/PageHeader";

export default function NewArtisanPage() {
  return (
    <>
      <PageHeader title="New Artisan" description="Identity, craft, place and notes." />
      <ArtisanForm />
    </>
  );
}

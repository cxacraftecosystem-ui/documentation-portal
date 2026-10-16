import { ProductForm } from "@/components/forms/ProductForm";
import { PageHeader } from "@/components/PageHeader";

export default function NewProductPage() {
  return (
    <>
      <PageHeader title="New Product Documentation" description="Details, pricing, craft and place." />
      <ProductForm />
    </>
  );
}

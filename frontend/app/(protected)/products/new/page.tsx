import { ProductForm } from "@/components/forms/ProductForm";
import { PageHeader } from "@/components/PageHeader";

export default function NewProductPage() {
  return (
    <>
      <PageHeader title="New Product Documentation" description="Record product details, economics, craft context, function, linked records and GPS." />
      <ProductForm />
    </>
  );
}

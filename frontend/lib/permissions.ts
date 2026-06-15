import type { User } from "@/lib/types";

export function isAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN" || user?.role === "ADMIN";
}

export function canEditOwnOrAdmin(user: User | null | undefined, ownerId?: string | null) {
  return isAdmin(user) || (!!user?.id && !!ownerId && user.id === ownerId);
}

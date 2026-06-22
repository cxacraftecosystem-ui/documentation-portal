import type { User } from "@/lib/types";

export function isAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN" || user?.role === "ADMIN";
}

export function isMasterAdmin(user: User | null | undefined) {
  return user?.role === "MASTER_ADMIN";
}

export function canManageQuestionnaire(user: User | null | undefined) {
  return isMasterAdmin(user) || !!user?.canManageQuestionnaire;
}

export function canManageCrafts(user: User | null | undefined) {
  return isAdmin(user) || !!user?.canManageCrafts;
}

export function canManageWorkshops(user: User | null | undefined) {
  return isAdmin(user) || !!user?.canManageWorkshops;
}

export function canDownloadDataset(user: User | null | undefined) {
  return isAdmin(user) || !!user?.canDownloadDataset;
}

export function canEditOwnOrAdmin(user: User | null | undefined, ownerId?: string | null) {
  return isAdmin(user) || (!!user?.id && !!ownerId && user.id === ownerId);
}

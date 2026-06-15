import asyncio
import os

from app.core.db import connect_db, db, disconnect_db
from app.core.security import hash_password


async def main() -> None:
    await connect_db()
    try:
        email = os.getenv("ADMIN_EMAIL", "admin@example.com").lower()
        name = os.getenv("ADMIN_NAME", "Repository Admin")
        password = os.getenv("ADMIN_PASSWORD", "ChangeMe123!")
        existing = await db.user.find_unique(where={"email": email})
        if existing:
            await db.user.update(
                where={"email": email},
                data={"name": name, "passwordHash": hash_password(password), "role": "ADMIN"},
            )
            print(f"Updated admin user: {email}")
        else:
            await db.user.create(
                data={
                    "email": email,
                    "name": name,
                    "passwordHash": hash_password(password),
                    "role": "ADMIN",
                    "authProvider": "LOCAL",
                }
            )
            print(f"Created admin user: {email}")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())

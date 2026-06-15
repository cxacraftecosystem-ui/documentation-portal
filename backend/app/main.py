from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.router import api_router
from app.core.config import get_settings
from app.core.db import connect_db, disconnect_db


@asynccontextmanager
async def lifespan(_: FastAPI):
    await connect_db()
    try:
        yield
    finally:
        await disconnect_db()


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title="Field Documentation Repository API",
        version="0.1.0",
        description="API-first backend for artisan, craft, workshop, product, tool, media and review records.",
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health", tags=["health"])
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    app.include_router(api_router)
    return app


app = create_app()

from fastapi import APIRouter

from app.api.routes import (
    artisans,
    auth,
    crafts,
    dashboard,
    export,
    media,
    products,
    questionnaire,
    review,
    search,
    tools,
    users,
    workshops,
)

api_router = APIRouter(prefix="/api")

api_router.include_router(auth.router)
api_router.add_api_route("/me", auth.me, methods=["GET"], tags=["auth"])
api_router.include_router(users.router)
api_router.include_router(artisans.router)
api_router.include_router(crafts.router)
api_router.include_router(workshops.router)
api_router.include_router(products.router)
api_router.include_router(tools.router)
api_router.include_router(media.router)
api_router.include_router(questionnaire.router)
api_router.include_router(dashboard.router)
api_router.include_router(search.router)
api_router.include_router(review.router)
api_router.include_router(export.router)

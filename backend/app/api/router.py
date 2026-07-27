from fastapi import APIRouter

from app.api.routes import (
    app_release,
    artisans,
    auth,
    crafts,
    dashboard,
    data_access,
    data_browser,
    export,
    feedback,
    map_points,
    media,
    preferences,
    processes,
    products,
    public,
    questionnaire,
    reference,
    review,
    search,
    secrets,
    settings,
    tasks,
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
api_router.include_router(processes.router)
api_router.include_router(tools.router)
api_router.include_router(media.router)
api_router.include_router(questionnaire.router)
api_router.include_router(dashboard.router)
api_router.include_router(search.router)
api_router.include_router(map_points.router)
api_router.include_router(review.router)
api_router.include_router(export.router)
api_router.include_router(data_browser.router)
api_router.include_router(app_release.router)
api_router.include_router(feedback.router)
api_router.include_router(preferences.router)
api_router.include_router(settings.router)
api_router.include_router(secrets.router)
api_router.include_router(data_access.router)
api_router.include_router(tasks.router)
api_router.include_router(reference.router)
# Last, and on its own line with this note, because it is the only unauthenticated router here:
# everything above is scoped to the caller, this one is scoped to nobody. See app/api/routes/public.py.
api_router.include_router(public.router)

from fastapi import APIRouter

from app.api.routes import (
    access_roster,
    ai_keys,
    answer_drafts,
    app_release,
    app_versions,
    artisan_cards,
    artisan_duplicates,
    artisan_merge,
    artisans,
    auth,
    completeness,
    craft_categories,
    craft_codes,
    crafts,
    cross_portal,
    cross_portal_search,
    dashboard,
    data_access,
    data_browser,
    datasets,
    export,
    feedback,
    map_coverage,
    map_points,
    media,
    notifications,
    preferences,
    processes,
    products,
    public,
    questionnaire,
    questionnaire_v2,
    recording_consent,
    reference,
    review,
    search,
    secrets,
    settings,
    tasks,
    tools,
    transcript_insights,
    transcription_admin,
    two_factor,
    users,
    workshops,
)

api_router = APIRouter(prefix="/api")

api_router.include_router(ai_keys.router)
api_router.include_router(auth.router)
api_router.include_router(two_factor.router)
api_router.add_api_route("/me", auth.me, methods=["GET"], tags=["auth"])
api_router.include_router(users.router)
# The sign-in gate's admin side. Mounted next to /users because it answers the same question from
# the other end: /users is who has an account, /access-roster is who is allowed to have one.
api_router.include_router(access_roster.router)
# The merge tool's literal /artisans/merge/* paths, ABOVE /artisans/{artisan_id}.
api_router.include_router(artisan_merge.router)
api_router.include_router(artisans.router)
api_router.include_router(artisan_duplicates.router)
# The artisan card: /artisans/{id}/card*, and /artisan-cards/* for status, sheets and resolving.
api_router.include_router(artisan_cards.router)
api_router.include_router(crafts.router)
api_router.include_router(craft_codes.router)
api_router.include_router(craft_categories.router)
api_router.include_router(cross_portal.router)
# The completeness rules file (GET /records/completeness-rules), for the clients' offline copies.
api_router.include_router(completeness.router)
# The designer portal's searches of this portal (signed peer routes; docs/SEARCH.md).
api_router.include_router(cross_portal_search.router)
api_router.include_router(workshops.router)
api_router.include_router(products.router)
api_router.include_router(processes.router)
api_router.include_router(tools.router)
# Before media: GET /media/insights would otherwise be taken by GET /media/{media_id}.
api_router.include_router(transcript_insights.router)
api_router.include_router(media.router)
api_router.include_router(answer_drafts.router)
api_router.include_router(questionnaire.router)
# Questionnaire v2 (dark behind QUESTIONNAIRE_V2; consent routes always on).
api_router.include_router(questionnaire_v2.router)
api_router.include_router(questionnaire_v2.artisans_router)
api_router.include_router(dashboard.router)
api_router.include_router(search.router)
api_router.include_router(map_points.router)
api_router.include_router(map_coverage.router)
api_router.include_router(review.router)
api_router.include_router(export.router)
api_router.include_router(data_browser.router)
# The bulk, admin-authenticated dataset API. Mounted like any other router; what makes it different
# is the credential it accepts (see app/api/routes/datasets.py and deps.require_dataset_admin).
api_router.include_router(datasets.router)
api_router.include_router(app_release.router)
api_router.include_router(app_versions.router)
api_router.include_router(feedback.router)
api_router.include_router(notifications.router)
api_router.include_router(preferences.router)
api_router.include_router(settings.router)
# The shared transcription service's admin panel: pause, providers, budgets (18 Oct 2026).
api_router.include_router(transcription_admin.router)
# Whether an artisan's recordings may be sent for transcription (the consent rule, 18 Oct 2026).
api_router.include_router(recording_consent.router)
api_router.include_router(secrets.router)
api_router.include_router(data_access.router)
api_router.include_router(tasks.router)
api_router.include_router(reference.router)
# Last, and on its own line with this note, because it is the only unauthenticated router here:
# everything above is scoped to the caller, this one is scoped to nobody. See app/api/routes/public.py.
api_router.include_router(public.router)

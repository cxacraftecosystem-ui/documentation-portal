from app.schemas.common import APIModel


class DigestPreferenceUpdate(APIModel):
    """The Monday summary's switch, on its own route so it saves (and shows) whether or not mail is on."""

    enabled: bool

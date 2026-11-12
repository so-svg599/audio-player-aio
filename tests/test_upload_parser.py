"""Regression fixture for YouTube WEB richItemRenderer/content/lockupViewModel."""
import json
import re
import unittest

ID = re.compile(r"^[A-Za-z0-9_-]{11}$")

FIXTURE = {
    "contents": {"twoColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {
        "content": {"richGridRenderer": {"contents": [
            {"richItemRenderer": {"content": {"lockupViewModel": {
                "contentId": "AbCdEfGhI_1",
                "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                "contentImage": {"thumbnailViewModel": {"image": {"sources": [
                    {"url": "https://i.ytimg.com/vi/AbCdEfGhI_1/hq720.jpg"}
                ]}}},
                "metadata": {"lockupMetadataViewModel": {"title": {"content": "Titre uploads actuel"}}},
                "rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {
                    "watchEndpoint": {"videoId": "AbCdEfGhI_1"}
                }}}},
                "contentOverlay": {"thumbnailBottomOverlayViewModel": {
                    "badges": [{"text": "12:34"}]
                }},
            }}}},
            {"richItemRenderer": {"content": {"lockupViewModel": {
                "contentId": "FallBack001", "metadata": {"lockupMetadataViewModel": {"title": {"content": "Sans image"}}}
            }}}},
        ]}}}
    }]}}}


def recursive_uploads(root):
    """Behavioral mirror of robustUploadTracks: recurse without type/duration gates."""
    found = {}
    def walk(value, lockup=False):
        if isinstance(value, dict):
            for key, child in value.items():
                if key == "lockupViewModel" and isinstance(child, dict):
                    add(child, True)
            add(value, lockup)
            for child in value.values():
                walk(child, lockup)
        elif isinstance(value, list):
            for child in value:
                walk(child, lockup)
    def add(obj, is_lockup):
        ids = []
        if isinstance(obj.get("videoId"), str) and ID.fullmatch(obj["videoId"]):
            ids.append(obj["videoId"])
        if is_lockup and isinstance(obj.get("contentId"), str) and ID.fullmatch(obj["contentId"]):
            ids.append(obj["contentId"])
        try:
            vid = obj["rendererContext"]["commandContext"]["onTap"]["innertubeCommand"]["watchEndpoint"]["videoId"]
            if isinstance(vid, str) and ID.fullmatch(vid): ids.append(vid)
        except (KeyError, TypeError):
            pass
        for vid in dict.fromkeys(ids):
            meta = obj.get("metadata", {}).get("lockupMetadataViewModel", {})
            title = meta.get("title", {}).get("content") or obj.get("title") or vid
            try:
                thumbnail = obj["contentImage"]["thumbnailViewModel"]["image"]["sources"][0]["url"]
            except (KeyError, IndexError, TypeError):
                thumbnail = f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"
            found.setdefault(vid, (title, thumbnail))
    walk(root)
    return found


class UploadParserRegression(unittest.TestCase):
    def test_current_web_lockup_extracts_and_deduplicates(self):
        result = recursive_uploads(json.loads(json.dumps(FIXTURE)))
        self.assertEqual(list(result), ["AbCdEfGhI_1", "FallBack001"])
        self.assertEqual(result["AbCdEfGhI_1"][0], "Titre uploads actuel")
        self.assertEqual(result["AbCdEfGhI_1"][1], "https://i.ytimg.com/vi/AbCdEfGhI_1/hq720.jpg")
        self.assertEqual(result["FallBack001"][1], "https://i.ytimg.com/vi/FallBack001/hqdefault.jpg")

    def test_id_only_video_renderer_is_accepted_without_duration(self):
        self.assertEqual(list(recursive_uploads({"unexpectedWrapper": {"videoId": "VideoId_001"}})), ["VideoId_001"])


if __name__ == "__main__":
    unittest.main()

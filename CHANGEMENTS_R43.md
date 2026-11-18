# R43 — Correctif Uploads YouTube

- Parseur Uploads indépendant des filtres de durée, contentType et nom de renderer : parcours récursif et extraction des `videoId` de 11 caractères, `lockupViewModel.contentId` et `watchEndpoint.videoId`, avec déduplication.
- Extraction du titre et de la miniature lockup; image YouTube `hqdefault.jpg` en repli.
- Replis InnerTube playlist uploads (`UU`/`VLUU`) avec WEB/ANDROID/TVHTML5, recherche liée à la chaîne, page chaîne HTML, puis RSS Atom public (jusqu'à 15 vidéos).
- Historique inchangé; les Uploads mettent leur résultat dans `streamResults` uniquement si l'onglet YouTube Uploads est toujours actif.
- Version 0.51, code 39. Test fixture WEB dans `tests/test_upload_parser.py`.

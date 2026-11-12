# R72 — téléchargements, parsing YouTube Music, SoundCloud, suggestions et ratio vidéo (1.3.2 / versionCode 53)

- `MainActivity.kt` : migration des ratios persistés non pris en charge vers « Ajuster »; signalement MediaStore après publication (IS_PENDING levé et notification/scan); affichage d’un pourcentage de téléchargement pour les flux directs lorsque la taille est connue.
- `YtMusic.kt` : extraction renforcée des lignes `musicResponsiveListItemRenderer` de Titres aimés, avec identifiant vidéo issu des données playlist/overlay/navigation, titre flexible et durée complémentaire; titres structurés conservés même si la durée est absente.
- `SoundCloudAccount.kt` : client_id renouvelé sur refus, nouvelle tentative avec id, puis fallback sans Authorization pour endpoints publics; erreurs non brutes, session conservée sauf expiration confirmée sur `/me`.
- `Ui.kt` : suggestions YouTube/SoundCloud dès deux caractères (debounce 250 ms), clic qui lance la recherche; progression visible et ratio limité à « Ajuster »/« Zoom » avec zoom Media3 centré.
- Validation statique de la syntaxe et de l’archive prévue; compilation Android et essais sur appareil/réseau non exécutés.

---

# R69 — correction de compilation Compose (1.3 / versionCode 51)

- `Ui.kt` : rétablissement de l’annotation `@Composable` sur `ArtworkImage`. Cette fonction utilise `LocalContext.current`, `produceState` et des composables d’interface (`Image`, `Box`, `Icon`); l’absence d’annotation provoquait les erreurs de compilation signalées. La vidéo YouTube R68 est, elle, déjà dans `YoutubeVideoArtwork` annotée `@Composable`; `remember`, `LocalContext.current`, `LaunchedEffect`, `DisposableEffect` et `AndroidView` y restent dans le corps composable, pas dans un callback UI non composable. Le menu de téléchargement ne réalise que des appels ordinaires à ses callbacks.
- Relecture des changements R68 dans `MainActivity.kt`, `StreamSources.kt` et `Ui.kt` : aucun autre appel de composable depuis les lambdas de téléchargement, de menu ou d’effet n’a été relevé.
- `app/build.gradle.kts` : version `1.3`, code `51`; texte de version de l’interface actualisé.
- Validation statique uniquement; aucun build Gradle/Android n’a été exécuté.

---

# R68 — téléchargements audio et vidéo YouTube dans Now Playing (1.2 / versionCode 50)

- `Ui.kt` : le menu du lecteur plein écran propose le téléchargement pour les pistes YouTube/SoundCloud uniquement; l’option est absente des pistes locales. Un bascule YouTube mémorisé active une surface vidéo dans l’emplacement carré de la pochette, coupée (audio muet) et resynchronisée périodiquement sur la position audio. La source vidéo utilise un flux NewPipe progressif disponible le plus proche de 360p; échec de résolution signalé dans l’emplacement.
- `StreamSources.kt` : résolution du flux vidéo progressif via NewPipe.
- `MainActivity.kt` : téléchargement réseau hors thread UI, résolution via le client YouTube ou SoundCloud existant, nom nettoyé « titre - artiste », insertion MediaStore sous `Music/Downloads` sur Android 10+ et toast de début/succès/erreur. Le type est déduit des en-têtes MIME/URL et l’extension suit le conteneur observé. Les flux non-MP3 sont conservés tels quels (pas de transcodage); sur Android 8/9, une permission d’écriture legacy est demandée et le fallback cible le dossier public `Music/Downloads`.
- `app/build.gradle.kts` : version `1.2`, code `50`, ajout du module `media3-ui` pour PlayerView.
- Vérifications statiques seulement. Compilation et essai sur appareil non disponibles/non effectués; fiabilité du flux vidéo dépendante des formats directs exposés par NewPipe. Flux audio YouTube/SoundCloud non nécessairement MP3 (ex. M4A/Opus/WebM), enregistrés sans conversion.

---

# R67 — correction du build YouTube Music (1.1 / versionCode 49)

- `YtMusic.kt` : `uploadMediaItem` exige quatre arguments, dont le renderer JSON. L’appel du fallback RSS à la ligne 533 n’en fournissait que trois; les deux appels issus des renderers JSON fournissaient déjà le renderer. Le fallback RSS transmet maintenant `JSONObject()` (aucun renderer structuré n’est fourni par le flux RSS), en conservant le comportement prudent des helpers sur métadonnées absentes.
- Vérification des trois appels à `uploadMediaItem` dans le projet : les trois transmettent désormais un renderer; les appels JSON passent leur objet renderer tel quel. Parenthèses et types de l’appel RSS relus.
- Version portée à `1.1` / `versionCode 49`; version d’interface `v1.1`.
- Vérifications statiques et archive effectuées; compilation Android non exécutée dans cet environnement.

---

# R66 — musique YouTube et fiabilité SoundCloud (1.0 / versionCode 48)

- `SoundCloudAccount.kt` : client_id découvert depuis soundcloud.com et /discover, HTML servi à deux UA, scripts sndcdn parcourus en ordre inverse et motifs multiples; candidats validés par l’API. Le dernier ID valide est conservé en SharedPreferences et jamais supprimé sur incident réseau. OAuth seul est essayé en priorité; repli client_id sur retry. Une déconnexion n’est signalée qu’après deux 401 confirmés de `/me`.
- `YoutubeMusicFilter.kt`, `YtMusic.kt`, `StreamSources.kt` : filtrage commun des mix, directs, titres non musicaux, podcasts non musicaux et durées supérieures à 12 min; sélection des types ATV/OMV/UGC, et pages de radio/continuation filtrées par le parser central. Les miniatures `hqdefault.jpg` restent inchangées.
- `Ui.kt` : version affichée `v1.0` et message d’échec contextualisé. Version `1.0`, code 48.
- Contrôles statiques uniquement; compilation Android et essais réseau/compte SoundCloud non effectués.

---

# R64 — miniatures YouTube et session SoundCloud (0.59 / versionCode 47)

- `YouTubeArtwork.kt` : helper central identifie le videoId depuis la page/mediaId et fournit `hqdefault.jpg` uniquement si l’artwork est nul/vide; un artwork existant reste inchangé.
- `StreamSources.kt`, `YtMusic.kt` et `LocalPlaylists.kt` : helper utilisé pour résultats de recherche/chaînes, accueil, historique, titres aimés, playlists, uploads et éléments restaurés. Les éléments portent ainsi la pochette dans leurs métadonnées Media3, dont la notification.
- `Ui.kt` : helper utilisé aux rendus des lignes de listes (dont accueil, playlists, historique et uploads), mini-lecteur et lecteur plein écran. Aucun changement au comportement de couverture SoundCloud/SMB/FTP.
- `MainActivity.kt` et `SoundCloudAccount.kt` : réutilisation des correctifs R63 (renouvellement client_id 20 min/au retour au premier plan, relecture jeton et retry unique après 401/403; déconnexion uniquement après second 401 et validation `/me`; jeton sauvegardé/effacé par commit). Texte d’expiration affiché : « Session expirée ».
- `SoundCloudAccount.kt` : fix compilation smart cast SoundCloudAccount.
- Version de l’application : `0.59`, `versionCode 47`.

---

# R60 — retrait de l’ajout SoundCloud depuis le menu du lecteur (0.57 / versionCode 45)

- `Ui.kt` : retrait de l’option d’ajout à une playlist SoundCloud du menu overflow du lecteur plein écran et suppression de son dialogue dédié. L’option Playlist YouTube et les autres commandes du lecteur restent inchangées.
- `MainActivity.kt` : suppression des callbacks et du gestionnaire SoundCloud devenus inutilisés dans le lecteur plein écran; gestion des playlists dans les autres écrans conservée.
- Version portée à 0.57 / versionCode 45. Vérifications statiques et archive effectuées; compilation Android non exécutée dans cet environnement.

---

# R59 — ajout aux playlists YouTube/SoundCloud et profils SoundCloud (0.56 / versionCode 44)

- `YtMusic.kt` : l’ajout YouTube utilise `browse/edit_playlist` sur `www.youtube.com`, le contexte `WEB`, `ACTION_ADD_VIDEO` et la couche HTTP authentifiée existante (cookie, SAPISIDHASH, Origin/X-Origin, Referer). Le préfixe `VL` est retiré de l’ID de playlist; l’ID vidéo est validé à 11 caractères. Les erreurs HTTP incluent code et extrait de réponse via le helper commun.
- `MainActivity.kt` : validation de l’ID vidéo extrait de l’URL YouTube avant l’envoi.
- `SoundCloudAccount.kt` : lecture de la playlist avec `representation=full`, conservation des identifiants numériques existants et ajout du nouvel ID; requête PUT JSON authentifiée avec Authorization OAuth, client_id actualisé une fois après 401/403, Origin/Referer/User-Agent et repli sur l’API legacy. Les erreurs rapportent codes HTTP et débuts des réponses.
- `Ui.kt` : retrait du clic profil dans les résultats SoundCloud et du mini-lecteur; clic conservé dans le lecteur plein écran.
- Version portée à 0.56 / versionCode 44. Vérifications statiques et archive effectuées; compilation Android et tests avec comptes réels non exécutés.

---

# R58 — renouvellement SoundCloud et ajout aux playlists (0.55 / versionCode 43)

- `SoundCloudAccount.kt` : client_id extrait dynamiquement des ressources JavaScript du site, cache de 30 minutes, reprise une fois avec nouvel identifiant après 401/403 ou réponse JSON illisible/vide. Les erreurs réseau/HTTP transitoires ne détruisent pas le jeton OAuth; le message de session expirée est réservé au refus 401/403 après la nouvelle tentative authentifiée. Ajout à une playlist par PUT en conservant les IDs existants.
- `YtMusic.kt` : correction du 404 en remplaçant l'ancienne route `playlist/add` par `playlist/edit` avec `ACTION_ADD_VIDEO` et `addedVideoId`. Les browseId `VLPL…` perdent seulement leur préfixe d'affichage `VL`, conservant le véritable identifiant `PL…`. L'appel réutilise contexte, cookie, SAPISIDHASH et headers communs des opérations Playlists.
- `MainActivity.kt` et `Ui.kt` : chargement du client_id SoundCloud au démarrage puis toutes les 30 minutes; rechargement des playlists YouTube/SoundCloud depuis les fonctions utilisées par leurs onglets à l'ouverture des dialogues « ajouter ».
- Validation statique des sources et archive; compilation Android et essais avec des comptes réels non exécutés dans cet environnement.

---

# R56 — saut des segments non musicaux et corrections ciblées (0.54 / versionCode 42)

- `PlaybackService.kt` : l’option existante « Ignorer les moments de silence » désactive désormais explicitement le processeur Media3 d’accélération/suppression PCM et interroge l’API SponsorBlock `music_offtopic` pour les vidéos YouTube. Les intervalles concernés sont sautés par `player.seekTo(finDuSegment)`; les autres médias ne sont pas touchés.
- `Ui.kt` : retrait de la ligne breadcrumb `/` dans les navigateurs SMB et FTP, sans toucher au bouton Retour. En cas d’échec de l’URI de pochette, extraction d’une image intégrée depuis le fichier audio local via `MediaMetadataRetriever` pour la liste, le mini-lecteur et la file.
- `app/build.gradle.kts` : version 0.54 / versionCode 42.
- Vérification statique des modifications. Build release et essais réseau/appareil à réaliser dans un environnement Android/Gradle configuré.

---

# R50 — flèche du lecteur et saut du silence (0.53 / versionCode 41)

- `Ui.kt` : flèche chevron toujours visible en bas du lecteur; cliquable pour ouvrir « Morceaux suivants » quand les gestes sont désactivés, indicateur avec balayage vers le haut quand ils sont activés. Zone au-dessus de la navigation système, avec `zIndex`.
- `PlaybackService.kt` : chaîne audio Media3 avec `SilenceSkippingAudioProcessor` (300 ms minimum, seuil PCM 1500, rétention nulle, au plus 100 ms conservées), activée uniquement pour YouTube via l’option existante et réappliquée au changement de pistes audio et à l’état prêt. Aucun changement de vitesse ajouté.
- Validation par inspection statique et intégrité du ZIP; compilation Android non lancée (pas de Gradle Wrapper dans l’archive).

---

# R44 — restauration de l’historique YouTube en ligne (0.52 / versionCode 40)

- `MainActivity.kt` : l’onglet Historique appelle `YtMusicClient.history()` avec le compte YouTube connecté; les erreurs détaillées de requête et de parsing remontent dans l’interface.
- `YtMusic.kt` : conservation du parcours FEhistory/WEB et FEmusic_history/WEB_REMIX, des diagnostics HTTP/clés et du parseur récursif large (dont `videoRenderer` et `lockupViewModel`).
- `PlaybackService.kt` : l’historique exposé via Android Auto vient désormais du compte en ligne; suppression de l’enregistrement local à la lecture.
- `Ui.kt` : l’historique YouTube n’est plus présenté hors connexion. Les Uploads, playlists et onglets restent inchangés.
- Suppression de l’ancien enregistrement historique dans le code et nettoyage de sa préférence persistée; version Android portée à 0.52 / versionCode 40.
- Contrôles statiques et test de parseur inclus; compilation Gradle à confirmer selon disponibilité de Gradle/wrapper.

---

# R42 — correction du parsing des Uploads YouTube (version 0.50 / versionCode 38)

- `YtMusic.kt` : le parseur de `lockupViewModel` accepte un ID vidéo de 11 caractères dans `contentId`, le `watchEndpoint.videoId` de `rendererContext.commandContext.onTap.innertubeCommand`, ou tout champ `videoId` imbriqué récursivement. L'absence de `contentType` reste admise, le type non-vidéo ne sert pas de filtre, et aucun filtre de durée/live ne peut écarter Shorts ou autres lockups ayant un ID. L'appel ciblé `collect(root, "lockupViewModel")` assure le traitement même d'un lockup pauvre en champs usuels; les traversées récursives existantes descendent dans `richItemRenderer.content.lockupViewModel`.
- Pour ce modèle, le titre demeure prioritaire depuis `metadata.lockupMetadataViewModel.title.content`, la miniature depuis `contentImage.thumbnailViewModel.image.sources`, la chaîne récupère explicitement les `metadataRows[].metadataParts[].text.content`; les badges de durée restent conservés. Un titre de repli par l'ID évite de perdre un lockup valide dont le titre manque.
- Les erreurs d'uploads incluent un extrait aplati (au plus 300 caractères) du premier `lockupViewModel` brut disponible; une réponse sans lockup l'indique explicitement.
- Aucun changement aux onglets ni aux autres données locales. Compilation Gradle et test réseau non exécutés.

---

# R40 — parsing robuste des Uploads YouTube (version 0.48 / versionCode 36)

- `YtMusic.kt` : sélection de l’onglet `Vidéos` déclaré sur la page chaîne avant l’essai avec les paramètres connus; conservation du parcours JSON récursif et amélioration des diagnostics des types de renderer.
- Continuations trouvées dans `continuationItemRenderer` et réponses append/reload; prise en charge des vidéos `lockupViewModel` (type vidéo, titre, vignette et durée des badges), ainsi que des structures rich-item et reel.
- Aucune modification de SoundCloud ni des playlists locales.
- Vérifications statiques du code, fixture JSON imbriquée et intégrité de l’archive; pas de Gradle/wrapper disponible pour compiler.

---

# R37 — correction Uploads et Historique YouTube (version 0.45 / versionCode 33)

- `YtMusic.kt` : Uploads reprend le flux compte connecté → `account/account_menu` / `FEmusic_library_landing` → conversion `UC…` en playlist `UU…`, d’abord `VL+UU…`, puis `UU…`; les anciens replis chaîne Vidéos, bibliothèque privée, `FEmusic_library_uploads` et playlist « Uploads » restent disponibles. Erreurs détaillées conservées.
- `YtMusic.kt` : Historique essaie d’abord `FEmusic_history` avec le client `WEB_REMIX` antérieur, puis `FEhistory/WEB` et variantes de secours; pagination et parsing multi-renderers R36 conservés avec diagnostics détaillés.
- Aucune modification de SoundCloud ni de `relatedTracks` (association titre → liste/mix).
- Version Android portée à `0.45` / `versionCode 33`.
- Validation statique des signatures, appels, délimiteurs et structure ZIP; compilation Gradle et essais réseau/appareil non exécutés.

---

# R36 — version 0.44 (versionCode 32)

- `Ui.kt` : la flèche `KeyboardArrowUp` du grand lecteur n'apparaît que lorsque « Gestes de balayage » est activé; son clic appelle `onOpenQueue`.
- `YtMusic.kt` : les uploads tentent d'abord la chaîne via WEB avec `browseId` de chaîne et les paramètres de l'onglet Vidéos R25, puis WEB_REMIX, les browseId d'uploads `VL+UU…`/`UU…`, et enfin les autres replis. Historique essaie d'abord `FEmusic_history/WEB_REMIX`, puis `FEhistory/WEB`, avec diagnostics détaillés pour les échecs.
- Le menu du grand lecteur ne contient aucune entrée d'ajout à une playlist locale et aucun callback/dialog correspondant; la navigation existante des playlists locales de la bibliothèque est conservée.
- Version Android conservée à `0.44` / `32`; package `com.so.svg599.musique`.
- Contrôles statiques : références d'ajout local du lecteur, version, package, délimiteurs; archive R36 à contrôler.

# R32 — version 0.40 (versionCode 28)

- `YtMusic.kt` : parsing vidéo récursif et tolérant des réponses YouTube Music, y compris les renderer video/grid/playlist/rich/lockup/music/tile; titre et miniature récupérés aussi dans les contenus imbriqués, sans durée obligatoire. Réutilisé par les flux Accueil, playlists, uploads, historique et titres aimés. Les réponses vides ne sont pas mises en cache côté Android Auto.
- `Ui.kt` : l’ajout à une liste locale reste absent du menu pour un titre SoundCloud; l’ajout à une playlist SoundCloud reste disponible si connecté.
- `app/build.gradle.kts` : `versionName` 0.40, `versionCode` 28; package `com.so.svg599.musique`.
- Validation statique des packages, délimiteurs et signatures/call sites; archive R32 créée et contrôlée avec `unzip -t`.

# R30 — version 0.38 (versionCode 26)

- `SoundCloudAccount.kt` : suppression des fonctions `addTrackToPlaylist` et `put` dupliquées, ainsi que des imports répétés. Conservation de l’implémentation unique PUT api-v2.
- `YtMusic.kt` : ordre d’essai de l’accueil étendu avec variantes WEB_REMIX/WEB et bibliothèque; Titres aimés essaie VLLL puis FEplaylist_aggregation et VLLM. Parseurs récursifs des structures vidéo conservés; Uploads privilégient l’onglet Vidéos chaîne UC via browseId et params dédiés.
- `PlaybackService.kt` : les listes vides ne sont pas mises en cache et les catégories YouTube/SoundCloud Android Auto respectent les réglages Android Auto.
- `Ui.kt` : section Apparence et titre Bibliothèques; catégories Android/Android Auto séparées, pochettes conservées sous Avancés, À propos et liens Discord/GitHub conservés. Lambdas d’actions unitaires explicites.
- Validation statique : équilibre des délimiteurs des 13 fichiers Kotlin, recherche des doublons SoundCloud, package obsolète absent. Aucun build Gradle exécuté conformément à la demande; tests réseau YouTube/SoundCloud non exécutés.

# R29 — version 0.37 (versionCode 25)

- `Ui.kt` : conversion explicite de la lambda de lien en `Unit` pour éviter la conversion expérimentale lors du callback Compose.
- Lecteur en cours : entrée « Ajouter à une liste », dialogue pour choisir une liste locale ou en créer une en y ajoutant le titre.
- Profils SMB/FTP : appui long avec Modifier, Renommer et Supprimer (confirmation).
- Identité Android mise à jour vers `com.so.svg599.musique` ; nom affiché « Musique ».

# R27 — version 0.35 (versionCode 23)

- `PlaybackService.kt` : fusion des deux implémentations identiques de `Player.Listener.onMediaItemTransition` dans un unique override; l’override répété causait une erreur Kotlin de redéclaration et bloquait `compileReleaseKotlin`. La mise à jour « ignorer les silences » est préservée dans l’override consolidé.
- `app/build.gradle.kts` : version portée à `0.35`, `versionCode` à `23`.
- Relecture statique des sources Kotlin modifiées, signatures/call sites, fichiers Gradle et règles R8. Aucun compilateur Kotlin ni wrapper/exécutable Gradle disponible dans l’environnement; build complet non exécuté.

# R24 — version 0.32 (versionCode 20)

- Qualité audio haute/basse persistée ; sélection du flux proche de la cible.
- Buffers augmentés, retry réseau avec reprise de position, pré-résolution du titre suivant et ajout différé des titres radio.
- Geste d’ouverture de file intégré dans l’option Gestes de balayage ; flèche conservée dans le lecteur.
- Égaliseur en deuxième position des paramètres. Ajout de titres aux playlists YouTube existantes ou création avec ajout.
- Uploads YouTube par InnerTube browse WEB connecté, onglet vidéos prioritaire, continuations et diagnostics détaillés.

# Version 0.31 (R23) — pagination de la radio YouTube

- `YtMusic.kt` : correction de l’appel à `nextContinuation` pour lui transmettre la réponse JSON initiale (`JSONObject`) en plus des identifiants vidéo, playlist et client; la fonction utilise cette réponse comme première page puis suit ses jetons de continuation. Aucun nouvel appel initial `next` n’est effectué pendant la pagination.
- Les paramètres sont nommés à l’appel. Une erreur de continuation est enregistrée et laisse les autres variantes/client radio essayer, sans arrêter le parcours.
- `app/build.gradle.kts` : version `0.31`, `versionCode` 19.
- Contrôle statique des appels et fonctions voisines; compilation Gradle non exécutée (wrapper/exécutable Gradle absent de l’archive et de l’environnement).

---

# Version 0.28 (R20)

- Lecteur plein écran : chevron de file remonté avec gestion des barres système, sans aide textuelle ni indication de clic; suppression du déclenchement de file par swipe vertical (la préférence demeure visible).
- YouTube : uploads chargés d’abord depuis la playlist UU de la chaîne du compte, puis onglet Vidéos; parcours des continuations et parseurs multi-renderers, avec cause d’échec affichée.
- Historique YouTube : tentative des browse IDs Music puis Web et pagination; fusion de l’historique YouTube distant avec les 100 derniers éléments locaux, les éléments locaux étant mis en tête.
- Accueil : le clic sur un élément d’accueil charge maintenant la radio RDAMVM avant de démarrer la file titre sélectionné + mix; la liste d’accueil ne sert qu’en fallback.
- Version Android mise à jour à 0.28 (versionCode 16).

# Changements R19 — indicateur de file et correctif de compilation (version 0.27)

## Modifications
- `Ui.kt` : opt-in explicite à `ExperimentalMaterial3Api` sur `NowPlaying`, qui contient `ModalBottomSheet`.
- Grand lecteur : remplacement du bouton de file en haut par un chevron blanc centré au bas de l’écran, cliquable pour ouvrir la file indépendamment du réglage de swipe; texte d’aide 12 sp affiché uniquement lorsque le swipe de file est activé. Le balayage bas-vers-haut existant est conservé.
- `app/build.gradle.kts` : version `0.27`, `versionCode` 15.

## Validation et limites
- Contrôle statique des usages d’API expérimentales et des modifications Kotlin; aucune autre API expérimentale explicite repérée.
- Compilation Gradle non exécutée dans cet environnement.

---

# Changements R18 — lecture YouTube, file et playlists (version 0.26)

## Modifications
- `StreamSources.kt` : extraction audio avec priorité aux flux HTTP progressifs, repli sur les autres formats audio fournis par NewPipe et une nouvelle tentative en cas d’échec. Le service retente les flux incompatibles, invalide le cache, saute au titre suivant et affiche un message.
- Uploads YouTube : les éléments conservent le MediaItem de watch URL YouTube canonique et utilisent le même résolveur de flux que les autres titres vidéo YouTube.
- Grand lecteur : menu YouTube uniquement « Ajouter à une playlist » et « Créer une playlist » via les endpoints InnerTube authentifiés existants; sélection/création, ajout du morceau lors de la création et retours toast succès/erreur. « Lire la liste associée » supprimé.
- Accueil YouTube : tentative de file radio/watch-next via endpoint `next` avec `videoId` et playlist `RDAMVM…`; repli sur les résultats de l’accueil en cas d’échec.
- Paramètres/lecteur : option persistante « Liste des morceaux suivants par swipe », geste bas-vers-haut désactivable, flèche toujours disponible et feuille inférieure cliquable affichant la file.
- `app/build.gradle.kts` : version `0.26`, `versionCode` 14. Règles R8/ProGuard inchangées.

## Validation et limites
- Contrôle statique des callbacks/imports, chemins `MediaItem`/URI, chaînes Kotlin et XML; archive à vérifier.
- Compilation Gradle et appels YouTube réels non exécutés dans cet environnement. Les endpoints InnerTube non documentés peuvent changer et nécessitent une session valide.

---

# Changements R17 — sessions, profils YouTube et listes associées (version 0.25)

## Modifications
- Sessions : cookies YouTube Music et jeton SoundCloud sont déjà sauvegardés chiffrés; synchronisation depuis les cookies WebView lors du retour dans l’application. Les erreurs d’authentification ou réseau ne déclenchent plus l’effacement automatique; seule la déconnexion explicite vide le stockage. L’ID client SoundCloud reste récupéré dynamiquement et retenté après rejet.
- Cause identifiée : YouTube utilisait un cookie SAPISID capturé une seule fois, puis invalidait définitivement le stockage et les cookies WebView dès qu’une erreur `YtAuthException` survenait, incluant 401/403. Le hash SAPISID était déjà recalculé par requête; NewPipe/WebView n’était pas le client des appels compte (InnerTube HTTP direct). SoundCloud persistait déjà `oauth_token` chiffré et rechargeait au démarrage et dans le service; le `client_id` était seulement mémoire, mais redécouvert après redémarrage.
- Chaînes : essais de résolution browseId artiste via YouTube Music, URL de chaîne/handle via NewPipe, onglet Vidéos et recherche de secours avec un message explicite si vide/indisponible.
- Uploads YouTube restauré dans la deuxième barre, après Playlists; lecture depuis la chaîne du compte via menu et playlist `UU…`, avec anciens fallbacks `FEmusic_library_uploads` et playlist enregistrée. L’option Uploads est réactivable dans les préférences et filtre la même clé que SoundCloud.
- Menu du lecteur : « Lire la liste associée » relance la liste source ouverte depuis le titre courant (YouTube/SoundCloud, listes locales et bibliothèque locale lorsque le titre est présent).
- `app/build.gradle.kts` : version 0.25, `versionCode` 13. `app/proguard-rules.pro` inchangé.

## Validation et limites
- Vérification statique à effectuer après modifications; compilation nécessitant wrapper Gradle/SDK non inclus dans R16.
- API YouTube Music et SoundCloud non officielles; pas de vrai refresh-token OAuth SoundCloud disponible dans les données stockées. Si le fournisseur révoque réellement un jeton ou invalide la session serveur, l’utilisateur devra se reconnecter; l’application ne supprime plus la session par erreur.

---

# Changements R16 — navigation, préférences et sources distantes (version 0.24)

## Modifications
- `Ui.kt` : retrait de l’onglet Uploads YouTube et de sa logique d’affichage; menus repliables pour les catégories Android Auto et les onglets de la deuxième barre, avec cases par onglet. Suppression du contrôle « Masquer la 2ème barre ».
- `MainActivity.kt` : préférences persistées des onglets désactivés; bascule vers un onglet restant si l’onglet courant est désactivé. Persistance Android Auto conservée.
- `YtMusic.kt` et `PlaybackService.kt` : suppression de la récupération des uploads YouTube devenus inaccessibles depuis les onglets de l’application et d’Android Auto. Les uploads SoundCloud sont conservés.
- `Ui.kt` : harmonisation SMB/FTP avec les listes pCloud (fil d’Ariane cliquable, retour, icônes et lignes); état de défilement dédié et retour à la position du morceau actuellement lu.
- `app/build.gradle.kts` : version `0.24`, `versionCode` 12. `app/proguard-rules.pro` inchangé.

## Validation et limites
- Relecture statique des signatures Compose/callbacks, références aux onglets et chemins de retour; vérification des préférences et de la configuration R8 existante.
- Compilation Gradle/R8 et essais sur appareil/réseau non exécutés dans cet environnement; dépend du serveur FTP (REST) pour reprise de flux, comme auparavant.

---

# Changements R15 — correctif R8 SMB/FTP (version 0.23)

## Modifications
- `app/proguard-rules.pro` : ajout des `-dontwarn` pour les références optionnelles Java SE et dépendances SMBJ/Mbassador/Commons Net, ainsi que des règles `-keep` demandées pour SMBJ, Mbassador et Commons Net.
- `app/build.gradle.kts` : vérification de la référence `proguard-rules.pro` dans le buildType `release`; ajout de l’exclusion `META-INF/*.kotlin_module` aux exclusions de packaging déjà présentes.
- Version incrémentée à `0.23` (`versionCode` 11).

## Validation et limites
- Vérification statique de la présence des règles, de leur référence par la configuration release, des exclusions de packaging et de la version; contrôle d’intégrité de l’archive.
- Build Gradle/R8 non exécuté : aucun wrapper Gradle fourni et aucun exécutable Gradle disponible. La validation porte sur la cohérence et la syntaxe textuelle des fichiers.

---

# Changements R14 — correctifs de compilation SMB/FTP (version 0.22)

## Modifications
- `PlaybackService.kt` : fusion des deux `onDestroy()` en un seul. Le nettoyage combine la disparition de l’icône, la désinscription des deux receivers, la libération du player et de la session Media3, la remise à null des références et l’arrêt des deux exécuteurs.
- `RemoteServers.kt` : correction de l’import `FileAttributes` vers `com.hierynomus.msfscc.FileAttributes`; détection du répertoire par masque binaire typé (`Long`), et ajout du `return` de la liste SMB.
- `RemoteServers.kt` : correction de l’appel Commons Net `FTPClient.connect` (retour `void`, appelé séparément du contrôle de `login`) et `FTPClient.setRestartOffset` (retour `Unit`/`void`, donc sans test booléen); le marqueur REST est posé avant `retrieveFileStream`, et un rejet du serveur remonte par le flux/protocole.
- `app/build.gradle.kts` : version 0.22 et `versionCode` 10. Les dépendances déjà déclarées SMBJ 0.13.0 et Commons Net 3.11.1 couvrent les API utilisées; aucune modification de dépendance nécessaire.

## Validation et limites
- Relecture statique de `RemoteServers.kt` : appels SMBJ (`SMBClient`, authentification, `DiskShare.list/openFile`, `File.read`), Commons Net (`FTPClient`/`FTPSClient`, listing, REST et flux de téléchargement), ainsi que les dépendances déclarées.
- Compilation non exécutée : l’archive ne contient pas de wrapper Gradle et aucun exécutable `gradle` n’est disponible dans l’environnement. Aucun test sur réseau ou appareil.

---

# Changements R13 — SMB et FTP (version 0.21)

## Modifications
- `RemoteServers.kt` (ajout) : profils SMB/FTP persistés dans EncryptedSharedPreferences; navigation SMBJ/Apache Commons Net; DataSource Media3 dédiée, lecture SMB par offset et reprise FTP via REST lorsque le serveur l’accepte. FTPS explicite et mode passif/actif FTP.
- `MainActivity.kt` et `Ui.kt` : écrans sources SMB/FTP, saisie des profils, navigation dossiers, filtrage des extensions audio et file de lecture du dossier courant; profils de secrets conservés chiffrés.
- `RemotePlayback.kt` : routage des URI SMB/FTP vers la DataSource distante en conservant le résolveur Media3 existant pour les autres flux.
- `PlaybackService.kt` : catégories SMB/FTP optionnelles dans Android Auto, profils et arborescence parcourables.
- `app/build.gradle.kts` : dépendances SMBJ 0.13.0 et Commons Net 3.11.1, exclusions META-INF, version 0.21 / versionCode 9.

## Validation et limites
- Relecture statique du câblage UI, de la navigation Android Auto, des URI de lecture et de la persistance chiffrée. Permission INTERNET déjà présente dans le manifeste.
- Compilation non exécutée : aucun exécutable Gradle ni wrapper Gradle n’est disponible dans l’archive source. Aucun test réseau/appareil effectué. La reprise FTP dépend de l’acceptation de REST par le serveur; SMB repose sur le port standard 445.

# Changements R19 — Uploads YouTube du compte

## Modifications
- `YtMusic.kt` : l’onglet YouTube « Uploads » tente d’abord de lire le menu du compte actif via le même endpoint interne, client HTTP et cookie/session existants. L’identifiant `UC…` est repéré dans les informations du compte ou le lien « Votre chaîne », puis converti en playlist uploads `UU…` et chargée avec le parser de playlist existant.
- `YtMusic.kt` : chargement des pistes de playlist avec la pagination/continuation déjà disponible pour les parcours browse; déduplication par identifiant média.
- Repli préservé : en absence d’ID, erreur ou playlist vide, l’ancienne recherche `FEmusic_library_uploads`, puis la playlist « Uploads » enregistrée, restent utilisées.
- `app/build.gradle.kts` : version 0.20, versionCode 8.

## Validation et limites
- Relecture statique des imports, fonctions HTTP, signatures, branches de fallback et pagination; aucune modification du mécanisme de client, des cookies ou de la session.
- Archive Android sans wrapper Gradle utilisable ici; aucun build ni essai réseau/appareil effectué. Le format précis du menu de compte peut évoluer côté YouTube; les fallbacks existants sont maintenus.

# Version R11 (travail partiel)

- Réglage d’affichage des pochettes (désactivé par défaut) et préférences catégories Android Auto.
- Entrée Égaliseur utilisant le panneau système, avec gestion de l’absence de panneau.
- Défilement pCloud vers le titre en cours au retour à la bibliothèque.
- Ajout d’un onglet Uploads pour YouTube Music.
- Icônes launcher standard/adaptatives régénérées à partir du logo fourni.
- Non réalisé dans cette livraison : intégration SMB/FTP et l’appel YouTube Data API demandé (le code utilise l’API interne YouTube Music existante). Compilation Android non validée faute de wrapper Gradle dans l’archive et d’exécutable Gradle local.

---

# Changements R18 — version 0.18

## Modifications
- `StreamSources.kt` : suppression de la réutilisation du cache trois heures pour SoundCloud. Les URLs signées sont maintenant résolues à nouveau à chaque démarrage de piste, et peuvent être invalidées explicitement après une erreur. Les autres sources gardent leur cache existant.
- `SoundCloudAccount.kt` : ajout d’un ordre alternatif de transcodings privilégiant HLS lors d’une nouvelle tentative, avec fallback sur les transcodings suivants et l’extracteur NewPipe.
- `PlaybackService.kt` : sur erreur de lecture SoundCloud (y compris HTTP 401/403/404 rapporté par Media3), ré-extraction unique de l’URL et tentative HLS avant le saut automatique. Une seconde erreur suit le mécanisme existant de saut, évitant les boucles.
- `app/build.gradle.kts` : version 0.18, versionCode incrémenté à 6.

## Cause identifiée
`StreamSources.kt` conservait l’URL directe de lecture pendant trois heures (`CACHE_MS`), alors que SoundCloud retourne des URLs signées susceptibles d’expirer plus tôt ou d’être refusées. En outre, `PlaybackService.kt` ne retentait la résolution que pour `UnrecognizedInputFormatException`; les erreurs HTTP de flux signé partaient donc directement au saut automatique. Cela correspond au symptôme « fonctionne juste après installation, puis peu de titres lisibles ».

## Validation et limites
- Relecture statique des branches de résolution, des types de retour et des signatures d’appels Kotlin modifiées; contrôle de l’intégrité du ZIP.
- Aucun build Gradle/Android ni essai sur appareil exécuté dans cet environnement.
- Le client_id est déjà gardé uniquement en mémoire et sa récupération est réessayée après 401/403 dans `SoundCloudAccount.kt` (`send`, lignes 166–183); aucune preuve ne permet d’attribuer spécifiquement le problème à son extraction. NewPipe est conservé comme fallback. La cause d’expiration varie selon les réponses de SoundCloud et n’a pas pu être validée par une lecture réseau réelle.

# Changements R17b — version 0.17.1

## Modifications
- `Ui.kt` : correction du scope de `listStateFor`. La fonction est définie localement dans `PlayerApp`, mais était également appelée directement depuis `Library`, où elle n’est pas visible. L’état de défilement stream, indexé par la clé de source/vue/liste, est maintenant obtenu dans `PlayerApp` et transmis à `Library` comme `LazyListState`. Les clés restent mémorisées par la map `remember` et les effets existants continuent de ramener la liste au morceau courant au retour.
- `app/build.gradle.kts` : version passée à 0.17.1 et versionCode incrémenté de 4 à 5.

## Validation et limites
- Relecture statique des références `listStateFor`, signatures/appels `Library` et `StreamPane`, ainsi que des imports et références Kotlin du projet; contrôle d’intégrité et inventaire du ZIP.
- Aucun build Gradle/Android n’a pu être exécuté dans cet environnement; la compilation complète doit être confirmée par le build Android/CI.

# Changements R17 — version 0.17

## Modifications
- `StreamSources.kt` : chargement natif de l’onglet Accueil/Featured d’une chaîne YouTube; seuls les `StreamInfoItem` (morceaux/vidéos) sont conservés, sans playlists/shelves/chaînes, dédupliqués par URL. Si l’accueil est absent, échoue ou ne fournit aucun morceau/vidéo, fallback sur l’onglet Vidéos.
- `MainActivity.kt`, `PlaybackService.kt` : suppression du toast d’erreur et saut automatique à la piste suivante après erreur de lecture/résolution. La progression reste en avant, sans retour au début; arrêt propre si la dernière piste échoue ou si toute la file échoue.
- `app/build.gradle.kts` : version 0.17, versionCode incrémenté à 4.

## Validation et limites
- Relecture statique des branches Kotlin modifiées, appels NewPipe et API Media3; vérification de l’intégrité du ZIP et de la liste des fichiers modifiés.
- Aucun build Gradle/Android ni essai sur appareil exécuté dans cet environnement.

# Changements R16 — version 0.16

## Modifications
- `Ui.kt` : état `LazyListState` conservé au niveau `PlayerApp` entre les vues; au retour à une liste, défilement animé vers le morceau en cours s’il est présent dans les résultats affichés. Le même état est réutilisé par les listes bibliothèque/stream.
- `Ui.kt`, `MainActivity.kt` : l’ouverture du profil d’une chaîne YouTube depuis le lecteur bascule directement vers la bibliothèque intégrée et affiche les vidéos du créateur dans le `StreamPane` natif (aucune page Web/WebView ni écran intermédiaire). Retour ferme d’abord cette vue et restaure le lecteur; le retour suivant ramène à la liste précédente. La lecture d’une vidéo de la chaîne utilise la liste complète chargée comme file d’attente.
- `StreamSources.kt` : chargement natif NewPipe des vidéos et pagination existante de l’onglet « Vidéos »; suppression du recours de secours au premier onglet pour ne jamais prendre une autre catégorie (par exemple les playlists) si l’onglet Vidéos n’est pas disponible.
- `app/build.gradle.kts` : version 0.16, versionCode incrémenté de 2 à 3.

## Validation et limites
- Relecture statique des branchements de navigation, signatures Compose, états de défilement, chargement chaîne et mise en file à la lecture; test d’intégrité et inventaire du ZIP.
- Aucun build Gradle/Android ni essai appareil exécuté dans cet environnement. L’extraction NewPipe dépend de la disponibilité et du format de l’onglet Vidéos renvoyé par YouTube.

# Changements R15 — version 0.15

## Modifications
- `Ui.kt` : retrait du menu d’ajout de piste aux listes depuis le lecteur, de l’action par résultat YouTube et de leurs dialogues; les panneaux d’affichage et d’ouverture des listes locales existantes sont conservés.
- `MainActivity.kt`, `Ui.kt`, `LocalPlaylists.kt` : suppression du callback d’écriture d’une piste dans une liste et des méthodes d’ajout/création devenues inutilisées; chargement, persistance existante et lecture des listes locales conservés.
- `YtMusic.kt` : filtrage des segments de sous-titre qui contiennent un compteur de vues, en conservant les autres segments de métadonnées.
- `Ui.kt`, `MainActivity.kt` : curseurs de vitesse et pitch quantifiés/arrondis à 0,01 sur la plage 0,50–2,00; 149 steps internes (150 intervalles), affichage à deux décimales et valeurs persistées arrondies à deux décimales. Aucun bouton +/- distinct n’existait.
- `app/build.gradle.kts` : version passée à 0.15, versionCode incrémenté de 1 à 2.

## Validation et limites
- Relecture statique des usages, signatures et imports modifiés; recherche des références d’ajout/création de listes, des références de compteurs de vues et des réglages au pas 0,05.
- ZIP recréé puis contrôlé par test d’intégrité et inventaire. Aucun build Gradle/Android ni essai appareil exécuté dans cet environnement.

# Changements R14 — version 0.14

## Modifications
- `MainActivity.kt` : suppression d’un bloc dans `ytLogout` qui référençait hors de sa portée `initialVisibleSources` et `defaultHiddenSources`. La définition et l’application des préférences de visibilité restent dans `onCreate`; le bloc était de plus inopérant, car `hiddenAtStart` garantit au moins une source visible.
- `app/build.gradle.kts` : version passée à 0.14.

## Validation et limites
- Relecture statique des références introduites en R13, des imports/signatures liés aux playlists et des portées Kotlin; aucun autre symbole R13 manquant n’a été identifié.
- Pas de compilation Gradle/Android ni d’essai appareil exécuté dans cet environnement.

# Changements R13 — version 0.13

## Modifications
- `MainActivity.kt` : Tout, SMB et FTP sont masqués au premier lancement (état mémorisé dans les préférences); les changements de visibilité sont persistés et si la catégorie active est masquée, sélection automatique de la première catégorie visible. Les catégories ne sont pas des entrées de l’arborescence Android Auto.
- `Ui.kt` : options « Icône de notification » et « Gestes de balayage » déplacées dans « Avancés » sans modifier leurs callbacks; ajout d’une action « Ajouter à une liste » sur chaque résultat YouTube, avec sélection d’une liste locale existante ou création d’une nouvelle liste et ajout du titre.
- `app/build.gradle.kts` : version passée à 0.13.

## Validation et limites
- Relecture statique des signatures Compose/Kotlin, imports utilisés, chemins d’ajout aux playlists, persistance des préférences et changement de source active. Les playlists YouTube restent des listes locales à l’application; aucune modification des playlists du compte YouTube Music n’est effectuée. Les éléments YouTube sont conservés sous leur URL de page et réutilisent la résolution de flux existante à la lecture.
- Le comportement local existant d’ajout aux listes n’a pas été modifié. Aucun build Gradle/Android ni essai appareil n’a été exécuté dans cet environnement.

# R11 — Correctifs lecteur et profils

- Suppression de la notification temporaire émise à chaque changement de piste. La notification de lecture Media3 et l’icône d’état du service restent intactes.
- Le nom du profil dans le mini-lecteur n’est plus cliquable. Le lien du grand lecteur reste actif pour YouTube et SoundCloud.
- Le profil YouTube ouvert depuis le grand lecteur affiche l’accueil de la chaîne dans une WebView intégrée à l’écran du lecteur. Retour ferme d’abord cette vue et ramène au lecteur; le retour suivant réduit le lecteur et ramène à la liste précédente. Pas de nouvel Activity ni d’arrêt de lecture.
- Version application : 0.11.

# Changements R9

## Modifications
- `StreamSources.kt`, `SoundCloudAccount.kt`, `YtMusic.kt` : ajout des informations d’identification/URL des créateurs aux métadonnées ; accès à la liste des titres SoundCloud d’un utilisateur par ID ou résolution du permalink, et accès à l’accueil artiste YouTube Music par browseId.
- `MainActivity.kt`, `Ui.kt` : noms d’artiste cliquables depuis la liste et le lecteur ; écran de profil intégré à l’application, chargement asynchrone des titres et fallback navigateur. Le menu ⋮ du lecteur rassemble vitesse/pitch et ajout/création de playlist.
- `LocalPlaylists.kt` (nouveau) : playlists locales et pistes stockées en SharedPreferences ; elles sont affichées dans les panneaux de bibliothèque YouTube/SoundCloud.
- `PlaybackService.kt` : ExoPlayer réglé à -10 s/+30 s, boutons personnalisés -10 s/+30 s et commandes seek back/forward autorisées dans MediaSession ; notification courte de changement de morceau, channel HIGH et timeout 5 s ; retrait de l’extension voiture utilisant des méthodes non disponibles à la compilation.
- `app/build.gradle.kts` : dépendance explicite `androidx.core:core-ktx:1.13.1` pour les API NotificationCompat. `AndroidManifest.xml` possédait déjà POST_NOTIFICATIONS ; la demande runtime API 33+ était déjà intégrée dans `MainActivity.kt`.
- `README.md` : section R9 et points de test appareil/Android Auto.

## Validation et limites
- Correction R9b : suppression de `CarExtender().setContentTitle(...)`, appel à l’origine de « Unresolved reference: setContentTitle » ; le titre/artiste restent affichés dans NotificationCompat.Builder. Vérification statique des sept fichiers Kotlin ciblés, des imports, signatures et références croisées ; la dépendance core-ktx est déclarée. Aucune compilation Gradle n’a été lancée/validée (pas de SDK Android ni de wrapper Gradle fourni).
- Les commandes seek standard Media3 répondent aux événements média usuels, mais Android Auto/le constructeur décide du routage des boutons physiques. Les boutons de layout personnalisés ne sont pas garantis visibles sur chaque écran/client Android Auto.
- La notification de transition standard utilise la priorité élevée et une expiration demandée à 5 s ; Android peut en réduire/masquer l’affichage selon permissions, réglages et canal. Les API YouTube Music/SoundCloud sont non officielles et peuvent évoluer.


# Changements R10

## Modifications
- `Ui.kt` : retrait de toute interaction sur le nom d’artiste dans les listes; l’artiste reste accessible uniquement depuis le mini-lecteur et le lecteur plein écran. Le profil est un panneau intégré superposé au lecteur plein écran, avec liste de titres lançables, fermeture et action Réessayer.
- `MainActivity.kt` : chargement intégré des titres d’artiste YouTube Music/SoundCloud, erreurs conservées dans l’interface et suppression complète de l’ouverture navigateur. Le profil reste affiché au-dessus du lecteur lorsqu’un titre du profil est lancé.
- `StreamSources.kt`, `PlaybackService.kt` : conservation et propagation des métadonnées de créateur (URL/permalink, identifiant SoundCloud ou browseId YouTube) jusque dans les MediaItems résolus par le service, lorsque les métadonnées de la liste en contiennent.
- `PlaybackService.kt` : création du canal de transition `IMPORTANCE_HIGH` au démarrage du service; notification de transition `MessagingStyle` / `CATEGORY_MESSAGE`, `Person`, RemoteInput, actions sémantiques Répondre et Marquer comme lu, publication via `NotificationManagerCompat`, contrôle de POST_NOTIFICATIONS. Les métadonnées de piste Media3 continuent d’être suivies par la session lors de chaque transition.
- `AndroidManifest.xml` : déclaration du récepteur privé non exporté pour les actions de notification. Le récepteur marque la notification comme lue et reçoit sans interface la réponse RemoteInput.
- `README.md`, `CHANGEMENTS.md` : notes R10 et procédure de vérification Android Auto.

## Android Auto et limites
Android Auto n’affiche ces notifications de messagerie que si les notifications de l’application sont autorisées dans Android et si l’utilisateur autorise les notifications de messagerie de l’application dans les réglages Android Auto. L’OS, le constructeur et les préférences Android Auto gardent le contrôle final de l’affichage heads-up. Le canal et la notification HIGH ne peuvent pas contourner un canal désactivé ou les réglages utilisateur.

Les actions Reply/Mark as read sont des actions de notification minimales : aucune réponse n’est envoyée à un service externe; « Marquer comme lu » ferme la notification. Certaines pistes peuvent ne pas fournir d’identifiant/lien de créateur par le fournisseur, auquel cas le nom d’artiste reste non cliquable dans le lecteur plutôt que d’ouvrir un navigateur. YouTube Music et SoundCloud reposent sur des API non officielles susceptibles d’évoluer.

## Validation
Relecture statique des imports, déclarations, signatures et références croisées Kotlin/Compose/Media3/AndroidX Core; contrôle du manifeste et recherche de chemins ouvrant un navigateur depuis le profil. Aucun build Android local n’a été lancé (SDK Android/Gradle local non fournis); compilation CI et essais appareil/Android Auto restent requis.

# Changements R12

## Modifications
- `MainActivity.kt` : le profil YouTube du grand lecteur charge les éléments vidéo de l’onglet vidéo de chaîne via NewPipeExtractor, dans la liste de pistes intégrée; sélectionner une piste lance la file du profil. L’état de la liste/source précédentes est sauvegardé et restauré en revenant au lecteur. La lecture existante continue pendant la navigation.
- `StreamSources.kt` : ajout de l’extraction du profil/onglet vidéo YouTube par `ChannelInfo` et `ChannelTabInfo`; seuls les éléments `StreamInfoItem` sont présentés, les éléments Live sont écartés, avec pagination bornée des vidéos.
- `YtMusic.kt`, `Ui.kt`, `MainActivity.kt`, `PlaybackService.kt` : l’accueil YouTube Music ne conserve/affiche que les morceaux et vidéos, sans collections/playlists (également dans la navigation Android Auto).
- SoundCloud et son écran de profil, la désactivation du clic profil dans le mini-lecteur, ainsi que les WebView de connexion restent inchangés. Aucune WebView de chaîne n’est utilisée.
- `app/build.gradle.kts` : version passée à 0.12.

## Validation et limites
- Relecture des fichiers Kotlin touchés, vérification des références aux propriétés d’accueil supprimées, du manifeste et des appels NewPipe; correction d’une erreur de syntaxe dans la branche de chargement du profil YouTube.
- Pas de compilation Gradle/Android ni d’essai sur appareil exécuté dans cet environnement; compatibilité effective des API NewPipe/chargement des chaînes doit être confirmée par le build CI et un test réseau/appareil. L’extraction du profil charge les résultats que fournit l’onglet vidéo de chaîne; la disponibilité et le contenu dépendent de YouTube/NewPipe.


# Changements R22 — version 0.30

## Modifications
- `Ui.kt` : retrait de « Ajouter à une playlist » du menu du grand lecteur et suppression des dialogues/callbacks devenus inutiles. « Créer une playlist » reste disponible.
- Cause réelle identifiée pour le mix : l'échec était masqué par le repli vers la file Accueil; en outre le parseur ne descendait pas systématiquement dans tous les wrappers, notamment `playlistPanelVideoWrapperRenderer`, et acceptait des réponses d'un seul titre. La file YouTube Home ne doit désormais plus être réappliquée après échec.
- `YtMusic.kt` : le chargement de la radio demande `/youtubei/v1/next` avec `RD<videoId>` en essayant les clients `WEB_REMOTE`, `WEB` puis `ANDROID_MUSIC`, puis `RDAMVM<videoId>`. Parsing des réponses incluant `playlistPanelVideoWrapperRenderer`, et repli sur `watch?v=<id>&list=RD<id>` / `ytInitialData`.
- `MainActivity.kt` : sur l'Accueil YouTube, remplacer la file entière par la radio uniquement si au moins deux pistes distinctes ont été analysées; sinon afficher un Toast détaillé. Plus de réapplication de la file d'accueil comme fallback. Un contrôle du titre actuellement joué empêche l'async de remplacer une autre lecture démarrée entre-temps.
- `YtMusic.kt` : les diagnostics d'Uploads et Historique incluent les erreurs HTTP/authentification ou, si HTTP 200 renvoie un JSON vide/non reconnu, les clés de réponse et les clés renderer détectées; les erreurs non vides apparaissent dans le panneau UI. Le cookie et l'auth SAPISIDHASH restent utilisés pour les requêtes YouTubei avec origine alignée au client.
- Cause concrète trouvée pour les Uploads : l'identifiant était fabriqué en `VLUU<suffixe UC…>` au lieu du browseId `UU<suffixe UC…>` attendu par la playlist uploads.
- `app/build.gradle.kts` : version `0.30`, code `18`.

## Validation / limites
- Vérification statique des appels menu, signatures, parcours récursifs et des accolades; aucune compilation Android n'a été exécutée. Les API YouTube internes peuvent changer; la réponse diagnostic est conçue pour exposer leur statut HTTP/structure au test réel.

# R28 — version 0.36 (versionCode 24)

- `MainActivity.kt` : cache YouTube accueil conservé séparément de la file; l’accueil SoundCloud est invalidé après lecture et rechargé au retour, tout en gardant la file liée à la sélection.
- `YtMusic.kt` et `StreamSources.kt` : exclusion des lives/badges live et entrées sans durée détectable dans les parsers/recherche YouTube.
- `PlaybackService.kt` : lecture de `skip_silence` depuis `player_settings`, appliquée au player réel sur le thread principal à l’initialisation et aux transitions; identification YouTube via l’URL de page.
- `SoundCloudAccount.kt` : accueil personnalisé du compte par flux/likes/historique et titres liés, tendance utilisée seulement en fallback.
- `Ui.kt` : section « À propos » et liens externes GitHub.
- Validation statique des accolades, signatures et appels; compilation Gradle non exécutée (aucun wrapper/exécutable Gradle dans l’environnement).

# R32 — version 0.40 (versionCode 28)

- `YtMusic.kt` : parcours récursif des réponses InnerTube maintenu et étendu aux wrappers `tileRenderer`, `musicTwoRowItemRenderer` et `musicResponsiveListItemRenderer`; extraction des vidéos par `videoId`/`contentId`, sans exiger une durée qui est souvent absente des réponses browse. Les directs explicitement marqués restent exclus. L’accueil parcourt les continuations et essaie les clients/browseId WEB_REMIX et WEB; playlists et aimés essaient des browseId alternatifs et extraient titres/IDs de plusieurs formes de renderer. Les erreurs incluent maintenant les clés/renderer reçus et les étapes tentées.
- `Ui.kt` : « Ajouter à une liste locale » et son dialogue sont masqués pour les titres SoundCloud; l’ajout à une playlist SoundCloud reste proposé.
- `app/build.gradle.kts` : version `0.40`, versionCode `28`.
- Cause vraisemblable de la régression observée : parser trop dépendant du champ durée pour qualifier les renderers vidéo; cette hypothèse correspond aux réponses browse R29/R30 décrites comme HTTP 200 mais sans pistes extraites. Le parsing tolère désormais les items sans durée, avec garde de l’identifiant vidéo et exclusion des lives marqués. Aucun test réseau avec compte réel ni build Gradle n’a été exécuté.

## R41 — version 0.49 (code 37)
- Parsing des lockupViewModel de l'onglet Vidéos: contentId ou watchEndpoint, titre lockup, vignettes contentImage/collection, durée des overlays et nom de chaîne; prise en charge récursive et conservation des anciens renderers.
- Les erreurs/résultats asynchrones Uploads ne sont publiés que si l'onglet Uploads est toujours actif.


## R71 — fiabilité audio, réseau lent et affichage vidéo (10 novembre 2026)

- YouTube : accepte les réponses audio MP4 et WebM dont le MIME comporte des paramètres `codecs`, sans refuser les autres MIME; conservation du format d’origine.
- SoundCloud : priorité MP3 progressif, puis Opus HLS et AAC HLS; extensions adaptées, récupération des segments HLS dans l’ordre avec segment d’initialisation, retries, fallback et vérification d’intégrité du fichier final.
- SoundCloud : renouvellement du `client_id` et nouvel essai après HTTP 401/403; les erreurs réseau ne suppriment pas la session enregistrée.
- Vidéo : modes Ajuster, Taille originale, 16:9, Zoom (rogne) et Étirer; réglage conservé dans les préférences.
- Réseau : délais allongés, retries sur échec de connexion, buffer plus important, cache disque borné et sélection automatique d’une qualité basse en cas de faible débit; pré-résolution du titre suivant conservée.

# LecteurAudio Android

Lecteur audio Android en Kotlin, Jetpack Compose et Media3, avec lecture locale et intégration WebDAV pCloud (première version à tester).

## pCloud via WebDAV

- Dans l'application, appuyer sur **pCloud** puis **Se connecter**.
- Région Europe : `https://ewebdav.pcloud.com` ; région USA : `https://webdav.pcloud.com`.
- Identifiant : e-mail pCloud ; mot de passe : mot de passe pCloud.
- L'application liste les dossiers et fichiers, permet d'ouvrir les dossiers et lance les fichiers audio compatibles (MP3, M4A, AAC, FLAC, OGG, OPUS, WAV, WMA, AIFF).
- Si « Mémoriser la connexion sur cet appareil » est cochée, les identifiants sont enregistrés via le stockage chiffré Android et la reconnexion est tentée au prochain lancement.
- Le dossier Crypto de pCloud n'est pas accessible via WebDAV. Si pCloud envoie un e-mail de confirmation de nouvelle connexion, il faut valider cette connexion.

## État et limites

- [x] Interface de bibliothèque et lecteur
- [x] Lecture de musique locale
- [x] Connexion WebDAV pCloud, parcours de dossiers et lecture distante implémentés
- [ ] Test réel avec un compte pCloud et vérification sur téléphone
- [x] YouTube et SoundCloud : recherche et lecture via NewPipeExtractor (à tester)
- [ ] SMB et FTP (pas encore intégrés)

Cette version est intégrée au code source mais n'a pas été compilée ni testée sur un appareil dans cet environnement. Le workflow GitHub Actions compile l'APK après envoi du projet.

## Compiler via GitHub Actions

1. Extraire le contenu du ZIP dans le dépôt GitHub (les dossiers `app`, `.github` et les fichiers Gradle doivent être à la racine du dépôt).
2. Aller dans **Actions** et lancer **Build APK** (ou pousser un commit).
3. Ouvrir l'exécution terminée et télécharger l'artefact `lecteur-apk`.

Le workflow lance `gradle :app:assembleDebug --no-daemon`.

## Corrections dans cette version
- Dans un dossier pCloud, Retour remonte au dossier parent sans fermer le lecteur.
- À l’accueil, deux appuis sur Retour en moins de 2 secondes quittent l’application.
- En plein écran de lecture, Retour revient à la bibliothèque sans arrêter la musique.
- Un balayage vers le bas sur la pochette réduit le lecteur ; un balayage vers le haut sur le mini-lecteur le rouvre.
- Dans pCloud, les fichiers non audio (par exemple APK, MP4, ZIP et documents) sont masqués ; seuls les dossiers et formats audio pris en charge restent visibles.
- La fenêtre de connexion pCloud propose « Mémoriser la connexion sur cet appareil ». Si la case est cochée, les identifiants WebDAV sont conservés dans un stockage chiffré Android et la reconnexion est tentée au prochain lancement. Décocher la case ou choisir « Déconnecter » efface les identifiants enregistrés.

La compilation et la connexion pCloud doivent encore être vérifiées par GitHub Actions et sur l'appareil : l'environnement de préparation ne dispose pas de Gradle installé pour compiler localement.


## Correctif navigation pendant la lecture
- La source sélectionnée est maintenant conservée dans l'état de l'activité, même lorsque l'écran du lecteur remplace temporairement la bibliothèque.
- En appuyant sur Retour pendant la lecture, le lecteur se réduit et la bibliothèque revient sur la même source (par exemple pCloud) avec le dossier actuellement affiché.
\n\n## Modifications de cette version\n- Lecture précédente/suivante : mise en file d'attente de tous les fichiers audio du dossier pCloud courant.\n- Double appui sur Retour à l'accueil : arrêt et vidage de la file de lecture avant fermeture de l'activité.\n- Notification multimédia Android avec icône de note de musique via Media3.\n

## Icône visible dans la barre d'état
- Ajout d'une notification Android classique séparée, avec l'icône monochrome `ic_music_note`, visible pendant la lecture.
- La notification multimédia Media3 existante reste responsable des commandes de lecture dans le panneau.
- L'icône classique est retirée quand la lecture est en pause, arrêtée ou terminée.
- Cette modification nécessite que les notifications de l'application soient autorisées dans Android.


## YouTube et SoundCloud
- Onglets **YouTube** et **SoundCloud** : taper une recherche, puis appuyer sur un résultat pour lancer la lecture (les résultats forment la file d'attente).
- Extraction via NewPipeExtractor (dépôt JitPack). Le lien audio est résolu au moment de la lecture, car il expire au bout de quelques heures.
- Usage personnel uniquement : ces services n'offrent pas d'API officielle pour ce type d'usage, et l'extraction peut casser quand ils changent leur site. Il suffit alors de monter la version de NewPipeExtractor dans `app/build.gradle.kts`.
- NewPipeExtractor est sous licence GPLv3 : si le dépôt est publié ou l'APK distribué, le projet doit aussi être sous GPLv3.
- Les identifiants pCloud ne sont envoyés qu'aux serveurs pCloud.

## Connexion YouTube Music (historique, playlists, titres aimés)
- Onglet **YouTube** → **Se connecter à YouTube Music** : la vraie page de connexion Google s'ouvre dans l'appli.
- Une fois connecté, les cookies de session sont stockés chiffrés sur l'appareil et envoyés uniquement à Google.
- Vues disponibles : Recherche, Historique, Aimés, Playlists. Un appui sur un titre lance la lecture de la liste.
- La lecture reste faite par NewPipeExtractor : l'écoute dans l'appli n'est pas ajoutée à l'historique YouTube.
- Seule la première page de chaque liste est chargée (pas de défilement infini pour l'instant).
- API interne non documentée : peut casser sans prévenir. Si la session expire, l'appli demande de se reconnecter.
- Contraire aux conditions d'utilisation de Google : usage personnel, à tes risques (un compte secondaire est plus prudent).

## Réglages YouTube Music
- La connexion et la déconnexion YouTube Music se font dans les **paramètres** (roue dentée).
- L'onglet YouTube s'ouvre directement sur **Playlists** (puis Historique, Aimés).
- La **loupe** en haut à droite lance les recherches YouTube et SoundCloud (touche Rechercher du clavier).

## Compte SoundCloud
- Paramètres (roue dentée) → **Se connecter à SoundCloud** : la page de connexion s'ouvre dans l'appli (la connexion par e-mail est la plus fiable ; Google, Facebook ou Apple peuvent être refusés dans une WebView).
- L'onglet SoundCloud s'ouvre sur **Playlists** (créées et likées), avec **Historique** et **Aimés**. La loupe lance les recherches.
- Le jeton de session est stocké chiffré et n'est envoyé qu'à SoundCloud. API interne non officielle : peut casser sans prévenir.

## Lecture, Android Auto et connexion
- **Suivant** : appui court = titre suivant ; appui maintenu = avance rapide (+10 s toutes les 0,5 s).
- **Android Auto** : la musique locale est masquée (provisoire). Pour la réafficher : `SHOW_LOCAL_IN_AUTO = true` dans `PlaybackService.kt`.
- **Connexion YouTube / SoundCloud** : bouton **Terminé** en haut de la page de connexion. Pour SoundCloud, le jeton est aussi relevé dans les appels du site.

## Corrections récentes
- **SoundCloud** : l'onglet s'ouvre sur **Uploads** (tes titres mis en ligne), puis Playlists, Historique, Aimés.
- **Lecture SoundCloud** : le lien audio est maintenant obtenu par le client SoundCloud de l'appli (même session que les listes), NewPipeExtractor ne sert plus que de secours. Les titres uniquement disponibles en HLS (.m3u8) sont relancés automatiquement avec le bon format (module `media3-exoplayer-hls`).
- **Android Auto** : nouveaux dossiers **YouTube Music** et **SoundCloud** (Playlists, Historique, Aimés, et Uploads pour SoundCloud). La connexion se fait sur le téléphone.
- **Paramètres → Avancés** : case « Masquer la 2ème barre » (notification supplémentaire).

## Mises à jour
- **Paramètres → Avancés → Masquer la 2ème barre** : cache la barre des sous-onglets (Uploads, Playlists, Historique, Aimés). La case « Icône de notification » (notification supplémentaire) reste dans les paramètres, comme avant.
- **Précédent** : appui court = titre précédent ; appui maintenu = retour rapide de 10 s toutes les 0,5 s.
- **Paramètres → Avancés → Dernière erreur de lecture** : affiche le dernier message d'erreur du lecteur (utile pour diagnostiquer Android Auto).

## Android Auto, gestes et catégories
- **Zapper** : un titre choisi dans une liste (YouTube, SoundCloud, pCloud…) met toute la liste en file d'attente, donc suivant/précédent fonctionnent.
- **Erreur « Impossible de charger votre sélection »** : les listes sont gardées 5 minutes en mémoire et le service répond maintenant à `getItem` et `subscribe`.
- **Onglet YouTube** : Accueil, puis tes playlists, puis Historique et Aimés. **Onglet SoundCloud** : Playlists, Historique, Aimés en tête, puis les uploads.
- **Paramètres → Gestes de balayage** (désactivé par défaut) : balayage gauche/droite = catégorie suivante/précédente ; dans le lecteur = titre suivant/précédent.
- **Paramètres → Catégories affichées** : masquer Tout, Local, pCloud, SMB, FTP, YouTube, SoundCloud (au moins une reste visible).

## Recherche, avance rapide et accueil
- **Appli, onglet YouTube** : la 2ème barre devient Accueil, Playlists, Historique, Aimés. L'accueil montre les recommandations de YouTube Music (titres, playlists, albums).
- **Android Auto, recherche** : le champ de recherche (et la commande vocale « joue … sur Lecteur ») cherche sur YouTube et SoundCloud.
- **Android Auto, -10 s / +10 s** : boutons ajoutés au lecteur (aussi dans la notification) ; les commandes standard avance/retour passent aussi à 10 s.
- **Android Auto, onglet YouTube** : Accueil puis tes playlists ; Historique, Aimés et « Musique que j'aime » sont retirés.


## Build Android optimisé (APK léger)

Le workflow GitHub Actions produit maintenant un APK `release` optimisé :
- R8 / ProGuard activé (`isMinifyEnabled = true`) pour réduire et obfusquer le bytecode ;
- suppression des ressources non utilisées (`isShrinkResources = true`) ;
- ressources de langue limitées au français ;
- signature debug pour permettre l'installation de l'APK de test obtenu depuis GitHub Actions.

L'APK est disponible dans l'artefact `lecteur-apk-release-light`. Cette signature est uniquement destinée aux tests : pour Google Play, il faudra signer avec une clé de publication personnelle et la conserver en sécurité.

Les modules nécessaires à YouTube/SoundCloud (NewPipeExtractor), à la lecture HLS, à Media3 et au stockage chiffré sont conservés pour éviter de supprimer des fonctions. La taille finale exacte ne peut être connue qu'après compilation ; elle dépend notamment des versions et des bibliothèques résolues par Gradle.


## Correctif compilation Release (R8 / Rhino)

- Règles R8 alignées sur les recommandations du projet NewPipe pour Rhino/NewPipeExtractor.
- Les références aux API Java SE `java.beans`, `javax.script` et `jdk.dynalink` absentes d'Android sont ignorées lorsqu'elles proviennent des chemins optionnels de Rhino.
- Le fichier de service `javax.script.ScriptEngineFactory` est exclu des ressources Android.
- La minification et la réduction des ressources restent activées.

La compilation Release doit être relancée dans GitHub Actions pour confirmer le résultat.


## Correction YouTube Music — playlists paginées
- La récupération de « Mes playlists » suit maintenant les jetons de continuation renvoyés par YouTube Music au lieu de s'arrêter à la première page.
- Les pages sont fusionnées en conservant l'ordre et en supprimant les playlists en double via leur browseId.
- La pagination est limitée à 30 pages supplémentaires pour éviter une boucle infinie si YouTube renvoie un jeton cyclique.

## Correctif de recherche YouTube / SoundCloud
- Les recherches NewPipe récupèrent désormais la première page et jusqu'à trois pages supplémentaires (maximum 80 titres), en dédupliquant les URL.
- Si une page suivante échoue, les résultats déjà obtenus sont conservés.
- La recherche SoundCloud via l'API v2 suit `next_href` / `offset` sur plusieurs pages (jusqu'à 150 résultats), avec déduplication.
- Android Auto peut afficher jusqu'à 25 résultats par source au lieu de 15.
- La recherche reste limitée par les résultats réellement fournis par YouTube/SoundCloud et par les restrictions de leurs services. Cette modification n'assure pas qu'une recherche retourne systématiquement 20 titres.

La compilation Android et les recherches avec les comptes réels doivent être vérifiées via GitHub Actions et sur l'appareil ; elles n'ont pas été exécutées dans cet environnement.

## Mise à jour de l'interface (octobre 2026)
- Le mini-lecteur et le lecteur plein écran reprennent maintenant la couleur d'accent sélectionnée dans les paramètres.
- Affichage des pochettes : MediaStore pour les fichiers locaux, miniatures YouTube et jaquettes SoundCloud lorsque les sources les fournissent. Les images sont chargées en arrière-plan et limitées à 640 px pour éviter de décoder de très grandes images.
- Le bouton Retour Android revient à la liste parente quand une playlist YouTube Music ou SoundCloud est ouverte.
- Ajout d'un réglage Vitesse et Pitch en haut à droite du lecteur plein écran (0,50× à 2,00×), avec mémorisation des valeurs et réinitialisation.
- Le nom de l'artiste/uploader est cliquable lorsque la source fournit une URL de profil : YouTube ouvre la page de la chaîne, SoundCloud ouvre le profil du créateur.

La compilation Release doit être validée via GitHub Actions ; elle n'a pas été exécutée dans cet environnement.


## Évolutions R9 — profils, playlists, Android Auto
- Appuyer sur le nom de l’artiste dans les résultats YouTube Music/SoundCloud ou dans le lecteur ouvre une page de titres du profil via l’API existante. Si la récupération échoue, le profil est ouvert dans le navigateur lorsque son URL est connue.
- Le menu ⋮ du lecteur plein écran contient le réglage vitesse/pitch et l’ajout à une playlist locale. Les playlists et les morceaux qu’elles contiennent sont persistés en SharedPreferences et listés dans les panneaux YouTube/SoundCloud.
- Android Auto / commandes MediaSession : incréments ExoPlayer de 10 s en arrière et 30 s en avant, commandes seek back/forward autorisées, et boutons de layout -10 s/+30 s. L’affichage des boutons personnalisés dépend du client Android Auto ; commandes physiques de volant/écran restent sujettes au routage et aux capacités du véhicule.
- Lors d’un changement de titre, une notification de transition haute priorité est publiée pour environ 5 s, avec titre et artiste ; pas d’extension Android Auto spécifique. Autoriser les notifications Android (API 33+) ; permission déclarée et demandée à l’exécution.
- À tester sur appareil : profils (connecté/non connecté et fallback), playlist créée puis relue après redémarrage, réglage du pitch, boutons de recherche sur Android Auto et permissions/notifications au changement de piste. La notification de transition peut être filtrée par le système, réglages de l’utilisateur ou client voiture.

La compilation et les tests sur appareil/Android Auto n’ont pas été exécutés dans l’environnement de préparation ; vérifier avec Gradle/GitHub Actions et un véhicule/appareil compatible.


## R10 — profils intégrés et Android Auto
- En mode liste, le nom d’artiste n’ouvre plus de profil et aucun compteur d’écoutes/vues n’est affiché. Pour ouvrir un profil, toucher le nom de l’artiste dans le mini-lecteur ou le lecteur plein écran. Le profil et ses titres restent dans l’application, au-dessus du lecteur; toucher un titre le lance. En cas d’échec, utiliser **Réessayer** dans cet écran. Aucun fallback navigateur n’est utilisé.
- À chaque transition, PlaybackService publie également une notification de type messagerie (`MessagingStyle`, `CATEGORY_MESSAGE`, actions répondre / marquer comme lu) après vérification de la permission de notification.
- **Android Auto :** autoriser les notifications de LecteurAudio dans les réglages Android et les notifications de messagerie de l’application dans les réglages Android Auto. Les préférences utilisateur, le canal système, le téléphone et le constructeur peuvent toujours décider de masquer les notifications heads-up.
- Les actions de réponse sont uniquement des actions d’interface Android Auto et ne transmettent pas de message; « Marquer comme lu » annule la notification. Les API YouTube Music/SoundCloud sont non officielles.


## R14 — version 0.14

Correctif de compilation : retrait d’une référence à des variables locales de `onCreate` depuis `ytLogout`. Les préférences initiales masquant Tout, SMB et FTP restent inchangées.

## R13 — version 0.13
- Les catégories **Tout**, **SMB** et **FTP** sont masquées par défaut; les cases de « Catégories affichées » les réaffichent et enregistrent la préférence. Une catégorie active est remplacée par une catégorie visible avant son masquage.
- **Icône de notification** et **Gestes de balayage** se trouvent désormais dans **Paramètres → Avancés**.
- Sur un résultat YouTube, l’action **Ajouter à une liste** permet d’ajouter le morceau à une playlist locale existante ou de créer une nouvelle liste. Les listes locales peuvent ainsi contenir des liens YouTube et des morceaux locaux.

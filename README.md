# yt-mp3 — application Android (v0.4.12)

Télécharge une **vidéo ou une playlist** YouTube, convertit en **MP3**, tague
(titre / artiste / année / album via MusicBrainz), ajoute **pochette** et
**paroles**, puis écrit dans le dossier Musique du téléphone.

Dépôt : <https://github.com/franckv6r/yt-mp3>
Releases : <https://github.com/franckv6r/yt-mp3/releases>

---

## Fonctionnalités

- téléchargement vidéo ou playlist, avec arrêt et sélection des morceaux ;
- MP3 192 kbps, tags ID3, pochette, paroles synchronisées si disponibles ;
- métadonnées MusicBrainz → iTunes → Deezer, avec préférence pour les albums
  studio (pas les compilations, singles ou bandes originales) ;
- analyse d'un dossier : pochettes / paroles / renommage des fichiers ;
- recherche dans les listes, pochettes d'album, sélection visible uniquement ;
- reprise de la liste après un passage en arrière-plan ou une rotation ;
- notification de progression et bouton **Arrêter** ;
- interface sombre, français / anglais / espagnol / allemand ;
- bouton **Vérifier les mises à jour de l'application** dans les paramètres.

---

## Installation

Depuis une release GitHub :

1. télécharger `yt-mp3_vX.Y.Z.apk` ;
2. autoriser l'installation d'applications inconnues pour le navigateur ou le
   gestionnaire de fichiers ;
3. ouvrir l'APK.

Les releases GitHub sont signées avec la clé de release du dépôt. Une APK de
debug ne peut pas être remplacée par une release signée avec une autre clé :
désinstaller l'ancienne APK une fois si nécessaire.

Pour le développement :

```bash
./gradlew --no-daemon :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

---

## Mises à jour de l'application

Au lancement, l'application affiche sa version dans le header
(`YouTube → MP3 · v0.4.12`) et interroge
`https://api.github.com/repos/franckv6r/yt-mp3/releases/latest`.

Si le tag de la dernière release est plus récent que la version installée, un
bandeau cliquable apparaît sous le header :

> Nouvelle version v0.4.6 disponible — installer

Un clic télécharge le premier `.apk` de la release et ouvre l'installateur
Android. Le contrôle est silencieux s'il n'y a pas de réseau ou pas encore de
release.

L'URL du dépôt est une constante développeur dans
`app/src/main/java/com/fp/ytmp3/Config.kt` :

```kotlin
const val DEFAULT_GITHUB_REPO = "franckv6r/yt-mp3"
```

Aucune URL n'est exposée dans les paramètres utilisateur.

### Releases automatiques

Workflows GitHub Actions :

- `.github/workflows/release.yml` : un tag `v*` construit et publie une release
  avec `app-release.apk` ;
- `.github/workflows/build-release.yml` : build + signature + création de la
  release ;
- `.github/workflows/upstream-extractor.yml` : tous les jours à 03:23 UTC,
  vérifie la dernière version de **NewPipe Extractor**. Si elle change, met à
  jour `plugin/build.gradle.kts`, crée un tag et une release.

L'application utilise **NewPipe Extractor**, pas `yt-dlp` : surveiller yt-dlp ne
changerait pas l'APK. Le workflow quotidien surveille donc
`TeamNewPipe/NewPipeExtractor`.

### Signature CI

Les workflows attendent quatre secrets GitHub :

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Voir [`docs/SIGNING.md`](docs/SIGNING.md). Le keystore et les mots de passe ne
doivent jamais être commités.

## Extracteur YouTube

NewPipe Extractor est compilé **directement dans l'APK** via le module `:plugin`.
Il n'y a plus de `.dex` chargé dynamiquement ni de mise à jour d'extracteur à
chaud : une nouvelle version de NewPipe passe par une nouvelle release APK,
créée par le workflow quotidien.

Le point d'entrée `plugin/src/main/java/com/fp/ytmp3/plugin/ExtractorApi.java`
expose :

- `info(url)` : flux audio + titre + chaîne ;
- `list(url)` : une entrée pour une vidéo, plusieurs pour une playlist.

Les URL `watch?v=…&list=RD…&start_radio=1` sont traitées comme **une vidéo** :
les mixes/radios YouTube ne sont pas des playlists finies.

---

## Construire

Prérequis : JDK 17, Android SDK (platform 34, build-tools 35), Gradle 8.7
(ou wrapper fourni).

```bash
export JAVA_HOME=$HOME/.local/opt/jdk17
export ANDROID_HOME=$HOME/.local/opt/android-sdk

./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

---

## Architecture

```text
com.fp.ytmp3 (Kotlin)
 ├─ MainActivity       écrans, jobs, listes, sélection
 ├─ SettingsActivity   nommage, batterie
 ├─ MusicBrainz        métadonnées, pochettes, paroles, cache
 ├─ GitHubUpdater      dernière release GitHub
 ├─ Updater            téléchargement + installateur APK
 ├─ Tagger / SafTagger tags ID3
 ├─ MediaStoreUtil     écriture MediaStore / SAF
 └─ LogStore           journal persistant 5 Mo

plugin (Java, NewPipe Extractor GPLv3, compilé dans l'APK)
 └─ ExtractorApi.info(url) / list(url)
```

---

## Limites

- YouTube change souvent : le workflow quotidien met à jour NewPipe Extractor
  et publie une nouvelle APK.
- Google Play Protect peut afficher un avertissement pour une application
  installée hors Play Store, même avec une APK propre. Xiaomi Security passe.
- Les fournisseurs cloud SAF peuvent ne pas exposer les pochettes intégrées.
- Une release signée avec une nouvelle clé nécessite une réinstallation.
- Le contrôle de version se base sur le tag GitHub ; les tags doivent suivre
  `vX.Y.Z`.
- L'appli est prévue pour un usage personnel, pas pour le Play Store.

## Licences

- NewPipe Extractor : **GPLv3** (sources du module `plugin` à fournir en cas de
  distribution).
- jump3r (LAME) : voir le projet d'origine (LGPL).
- mp3agic : MIT.

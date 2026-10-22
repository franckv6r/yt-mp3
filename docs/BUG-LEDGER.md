# Bug ledger — yt-mp3 Android

## 2026-10-21 — Ben Harper « Jah Work » : rien trouvé, tags faux
- Symptôme : fichier `2009 - noliv008 - Ben Harper Jah work.mp3`, année 2009, artiste `noliv008`, pas d'album/pochette/paroles ; l'analyse dossier ne trouvait rien.
- Cause racine : la recherche d'album interrogeait MusicBrainz avec le **titre du morceau** (`releasegroup:"Jah Work"`) au lieu de lire les sorties du recording ; découpage artiste/titre naïf (aucun split quand un séparateur existait, tags faux réinjectés) ; requêtes MusicBrainz en rafale sans respect du 1 req/s ni retry ; aucun secours si MusicBrainz échoue.
- Fix : `MusicBrainz.kt` — requête générique sur le titre complet, matching par inclusion titre+artiste/chaine, `pickRelease()` sur les releases du recording (bonus studio), rate-limit + retry, fallback iTunes (cache/throttle) puis Deezer, cache positif, cover CAA → Deezer/iTunes, paroles LRCLIB avec recherche de secours. `MainActivity.kt` passe le titre seul + l'artiste comme indice.
- Non-régression : `gradle :app:testDebugUnitTest` (`MetadataTest`) ; sur téléphone `Ben Harper Jah work` → `1997 - Ben Harper - Jah Work.mp3`, album *The Will to Live*, APIC + USLT présents. Test `noiseOnlyTitleStillMatches` pour « Music ».
- Occurrences : 1.

## 2026-10-21 — Dossier SAF : renommage impossible, doublon `(1)`
- Symptôme : un fichier à la racine du dossier choisi n'était pas renommé en place ; au 2e passage sur un fichier déjà bien nommé, création d'un `… (1).mp3` puis suppression de l'original.
- Cause racine : `FileMover.findParent` partait de l'URI **tree** au lieu du document racine ; `renameDocument`/`moveDocument` recevaient une URI tree ; `createDocument` ne peut pas créer un dossier avec une URI tree. Repli systématique copie+suppression, avec collision sur le nom existant.
- Fix : `MediaStoreUtil.rootDocument()` ; `children()` accepte tree et document ; `findParent` part du document racine ; `publish()` et `findOrCreateDir()` créent depuis le document racine.
- Non-régression : exécution « Pochettes + paroles » deux fois sur `1997 - Ben Harper - Jah Work.mp3` → nom inchangé, aucun `(1)`, tags/cover/lyrics intacts.
- Occurrences : 1.

## 2026-10-21 — Correctifs de revue
- **Tags effacés** : `SafTagger.write` supprimait album/année quand la résolution était partielle → on ne modifie un tag que si la nouvelle valeur est non nulle.
- **Document 0 octet** : `MediaStoreUtil.publish` laissait le document créé si la copie échouait → suppression du document/URI en cas d'échec.
- **Titres uniquement « bruit »** : `tokens()` filtrait aussi le titre candidat (« Music », « Audio ») → titre vide, jamais résolu ; le bruit n'est filtré que sur la source brute.
- Non-régression : `noiseOnlyTitleStillMatches` ; build + tests ; les deux autres sont des gardes défensives vérifiées par relecture.
- Occurrences : 1 chacun.

## 2026-10-21 — Retour d'arrière-plan : crash BadTokenException
- Symptôme : l'appli disparaît quand on la quitte puis qu'on y revient ; logcat : `WindowManager$BadTokenException: Unable to add window -- token null is not valid`.
- Cause racine : `urlInput.setOnFocusChangeListener` appelait `showDropDown()` pendant `onRestoreInstanceState`, avant que la fenêtre de l'activité soit valide.
- Fix : suppression de l'ouverture sur focus ; le dropdown ne s'ouvre plus que sur un clic explicite, avec garde `isAttachedToWindow` + adapter non nul.
- Non-régression : quitter/revenir sur l'appli, puis vérifier l'absence de `FATAL EXCEPTION` dans logcat et l'ouverture de l'historique au clic.
- Occurrences : 1.

## 2026-10-21 — Notification de progression : cas limites (revue)
- **Notification orpheline** : si `startForegroundService` échoue (restriction Android 12+/MIUI), `Notify.start` laissait `active=true` et postait une notification ongoing sans service. Fix : `active=false` + retour, aucune notification.
- **Service détruit par le système** : `onDestroy` annule la notification mais laissait `active=true` → `deactivate()`.
- **Barre à 100 % sans Content-Length** : `Http.download` renvoyait `done/1024` interprété comme un pourcentage. Fix : `-1` = indéterminé, l'UI et la notification affichent « Téléchargement… ».
- Non-régression : build + tests ; le cas « sans Content-Length » reste à couvrir par un serveur de test.
- Occurrences : 1 chacun.

## 2026-10-21 — Changement de dossier : liste de playlist non rafraîchie
- Symptôme : après analyse d'une playlist dans un dossier vide (tout coché), le choix d'un dossier contenant les morceaux ne mettait pas à jour les cases ; il fallait relancer « Analyser la playlist » (YouTube).
- Cause racine : la présence était calculée uniquement dans `analyze()` ; le callback du sélecteur de dossier ne changeait que `destUri`.
- Fix : `refreshPresence()` recalcule localement `NameMatcher` sur le nouveau dossier, sans appel réseau ; déclenchée au changement de dossier, différée si analyse/téléchargement en cours, ignorée si le panneau dossier est affiché, si le dossier est réaccessible, ou si le dossier est identique (préserve les cases manuelles).
- Non-régression : `empty` → 4 morceaux cochés « à télécharger » ; retour `sarah` → 4 « déjà présent » décochés ; re-sélection du même `sarah` → case cochée manuellement conservée.
- Occurrences : 1.

## 2026-10-22 — Job pochettes/paroles qui semble bloqué, Arrêter sans effet
- Symptôme : après « Pochettes + paroles » sur un morceau, le fichier était mis à jour mais le job restait affiché en cours ; « Arrêter » ne changeait rien. Fichiers de `cremaillere` modifiés à 03:24, 03:28 et 03:33 ; processus tué à 03:34 par `OneKeyClean` (MIUI), service de premier plan encore actif.
- Cause racine : aucune trace persistante (journal en RAM, logcat réécrit) ; `Arrêter` ne posait qu'un drapeau, sans annuler la coroutine ; les replis pochette/paroles pouvaient enchaîner plusieurs timeouts (plusieurs minutes) ; le renommage SAF n'avait aucune limite.
- Fix : journal fichier horodaté avec rotation 5 × 1 Mo (5 Mo max), traces par étape ; `requestStop()` annule réellement les jobs ; vérifications d'arrêt entre les sources MusicBrainz/iTunes/Deezer/CAA/LRCLIB (y compris MusicBrainz, timeouts 8 s/15 s) ; renommage plafonné à 30 s, suivi et rafraîchissement de liste différé tant qu'il tourne ; au démarrage seuls les deux fichiers de log les plus récents sont relus (2 Mo max) ; boutons « Batterie : sans restriction » et « Autostart MIUI » dans les réglages.
- Non-régression : 15 tests + build ; job pochettes tracé étape par étape ; « Arrêter » → « Arrêté » sans écriture ; rotation vérifiée (5 fichiers, plus ancien supprimé) + test JUnit ; journal relu après redémarrage du processus ; batterie sans restriction et autostart activés sur le téléphone.
- Occurrences : 1.

## 2026-10-22 — Renommage SAF bloqué 30 s pour un nom inchangé
- Symptôme : « Pochettes + paroles » sur `2020 - Khruangbin - Pelota.mp3` affichait `renommage abandonne (delai 30 s)` puis `fait`, alors que le fichier était correct.
- Cause racine : `FileMover.findParent()` parcourait récursivement les 387 fichiers du dossier pour retrouver le parent à chaque renommage, puis dépassait le timeout de 30 s.
- Fix : chemin rapide qui déduit l'ID du parent depuis l'ID du document (ExternalStorageProvider), avec repli sur le parcours récursif pour les autres fournisseurs.
- Non-régression : même fichier renommé en 0,57 s, aucun log d'abandon, aucun doublon.
- Occurrences : 1.

## 2026-10-22 — Otis Redding « Papas Got a Brand New Bag » sans pochette/paroles
- Symptôme : le fichier recevait ses tags de base mais aucune APIC/USLT, car aucun catalogue n'était retenu.
- Cause racine : `Papa's` était découpé en `papa` + `s`, donc différent de `Papas` ; de plus un titre catalogue suffixé `(Live)` n'atteignait pas le seuil de couverture 0,9 même avec l'artiste confirmé.
- Fix : suppression des apostrophes avant tokenisation (`MusicBrainz.tokens`, `NameMatcher.norm`) et seuil abaissé à 0,82 uniquement quand l'artiste est déjà confirmé.
- Non-régression : tests `apostropheInCatalogTitleMatchesFileWithoutIt`, `liveSuffixDoesNotBlockConfirmedArtist`, `fileWithPapasMatchesTitleWithApostrophe` ; sur téléphone, pochette 130 Ko + paroles USLT et renommage en `1968 - Otis Redding - Papa's Got a Brand New Bag (Live).mp3`.
- Occurrences : 1.

## 2026-10-22 — Liste perdue après passage dans une autre application
- Symptôme : au retour, l'activité recréée (ou le processus tué par MIUI) repartait avec les listes vides.
- Cause racine : aucun état de liste n'était persisté ; `onCreate` repartait de zéro.
- Fix : sauvegarde JSON des pistes/fichiers + URL + mode + option récursive dans `SharedPreferences` à `onPause` (et après analyse/jobs) ; restauration dans `onCreate` ; revalidation locale de la présence des pistes et relecture du dossier pour éviter les URI périmés.
- Non-régression : après `am force-stop` puis relance, playlist de 4 titres restaurée (2 présents, 2 à télécharger) et dossier de 387 fichiers restauré.
- Occurrences : 1.

## 2026-10-22 — Loader d'analyse invisible quand une liste est affichée
- Symptôme : après « Analyser », la barre indéterminée restait sous les listes, hors écran.
- Cause racine : la carte Progression était placée après les sections playlist/dossier.
- Fix : carte déplacée juste après les boutons d'analyse ; arrêt explicite de l'indicateur dans les `finally` d'analyse (y compris sur erreur).
- Non-régression : capture pendant l'analyse (barre indéterminée visible) et après (barre pleine 4/4, plus d'animation).
- Occurrences : 1.

## 2026-10-22 — Fichier bien nommé renommé avec l'artiste des tags faux (UNCLEFTY)
- Symptôme : `1977 - Talking Heads - Psycho Killer.mp3` avait des tags `UNCLEFTY` / album `Found a Job` ; l'analyse dossier a fait confiance aux tags et l'a renommé `UNCLEFTY - Psycho Killer.mp3`.
- Cause racine : `SafTagger.read()` priorisait les tags ID3 sur le nom ; aucun indice du nom n'était utilisé. Le cache pouvait aussi resservir une compile (`CBGB`, 2013) et les anciens TPE2/TXXX/MusicBrainz restaient dans le fichier.
- Fix : nom de fichier utilisé comme indice artiste/titre avec repli sur les tags ; cache v3 ; exclusion des bandes originales ; bonus aux enregistrements canoniques (nombre de sorties) et limite MB 25 ; nettoyage ciblé des TXXX/UFID périmés et album artist seulement s'il était vide ou égal à l'ancien artiste.
- Non-régression : test `soundtrackDoesNotBeatAStudioAlbumEvenIfOlder` ; sur téléphone, le fichier redevient `1977 - Talking Heads - Psycho Killer.mp3`, album `Talking Heads: 77`, TPE1/TPE2 `Talking Heads`, APIC + USLT, sans IDs UNCLEFTY. 23 tests OK.
- Occurrences : 1.

## 2026-10-22 — Rotation en paysage : job tue
- Symptôme : passer en paysage pendant un telechargement ou une recuperation de pochettes/paroles annulait le job.
- Cause racine : la rotation recreait `MainActivity` ; `onDestroy()` annulait le `CoroutineScope` de l'activite et le job avec.
- Fix : declaration `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"` sur `MainActivity` ; l'activite n'est plus recreee pour une rotation, le job et la progression continuent.
- Non-regression : job de 3 telechargements lance, rotation en paysage en cours de route : PID inchange, progression 10→30 %, aucun nouveau demarrage d'activite ; arret manuel correct. Build + 23 tests.
- Occurrences : 1.

## 2026-10-22 — Barre de progression invisible pendant un fichier en cours
- Symptôme : pendant le telechargement/conversion d'un fichier, la barre restait a 0 (aspect vide) alors que le job continuait ; l'utilisateur croyait qu'elle avait disparu.
- Cause racine : `updateStats()` n'avance la barre qu'entre deux fichiers ; pendant le travail d'un fichier (parfois plusieurs minutes), elle restait a 0.
- Fix : `startItemProgress()` passe la barre en mode indetermine au debut de chaque fichier (telechargement et pochettes/paroles), puis `updateStats()` reprend la progression determinee a la fin du fichier ; remise a zero dans les `finally`.
- Non-regression : capture pendant un telechargement : segment bleu anime visible sous la carte Progression, a cote du bouton Arreter ; build + 23 tests.
- Occurrences : 1.

# État LocalDictate

## Situation au 31 octobre 2026

Dépôt privé : https://github.com/juliendevelopment/localdictate . Checkout canonique : `~/dev/sources/localdictate`. Les agents Codex de la phase précédente restent arrêtés ; ne pas les relancer.

La **dictée** est livrée en APK signés (releases GitHub `android-v0.1.0` à `android-v0.1.8` ; v0.1.8 publiée au SHA `0c7cae2`, APK téléchargé et vérifié : checksum, certificat `07780567…efe36`, code de version 108), installés et essayés par le mainteneur sur son Pixel avec Google Keep et Gboard. Le mode **réunion** n’est pas dans ces APK (`work/meeting-core` non fusionnée).

Vérifié sur le téléphone par le mainteneur :

- installation (après désactivation temporaire de Play Protect) et mises à jour par-dessus avec la même clé ;
- permissions, y compris « Autoriser les paramètres restreints » pour l’accessibilité ;
- préparation des modèles Parakeet + Silero, chargement JNI, reconnaissance française correcte ;
- bulle armée, déplaçable, ● Dicter / ■ Arrêter ;
- insertion réelle au curseur dans Keep, mais **pas de façon fiable** jusqu’à v0.1.7 ;
- copie automatique de secours et historique des 5 derniers textes (v0.1.6, v0.1.7).

**L’insertion échouait par une erreur de conception** (exiger un « monde figé » pendant la dictée), corrigée par la refonte v0.1.8. Analyse complète, journal des essais version par version, règles actuelles et lecture des messages : **[insertion.md](insertion.md)**.

## Prochaine action

0. le mainteneur essaie v0.2.0 : début de phrase et mots entre deux phrases (correctif v0.1.9), puis la nouvelle bulle (rendu vérifié seulement sous Robolectric, `FloatingControlTest` ; images avec `BUBBLE_SNAPSHOTS=/dossier`).

1. Faire refaire à le mainteneur avec v0.1.8 : plusieurs dictées de suite dans Keep sans toucher l’écran entre deux (note vide puis remplie), une dictée de 5 min, puis Messages et un champ web.
2. Si un refus subsiste, lire la raison affichée (tableau dans `insertion.md`) et corriger **la règle concernée** ; ne pas réintroduire d’invalidation par événements ni d’exigence de session figée.
3. Ensuite seulement : endurance 10–15 min (mémoire, chauffe), puis réconciliation de `work/meeting-core`.

## Bulle demandée (31/10/2026)

Référence Wispr Flow envoyée par le mainteneur : au repos, petit carré arrondi au bord droit avec une icône d’onde ; pendant la dictée, une pilule [✕ annuler] [onde animée] [✓ terminer]. Glisser la bulle vers une cible ✕ pour la masquer ; elle réapparaît quand on rallume/déverrouille le téléphone. **Implémentée en v0.2.0** (`session/FloatingControl.kt`).

## Releases et procédure

Chaque correctif publié : notes `docs/releases/vX.Y.Z.md` (obligatoires pour le workflow), commit sur `main`, push, attendre **Android Build** vert au SHA exact, puis tag annoté `android-vX.Y.Z` sur ce SHA et push du tag. **Android Release** construit, signe, vérifie (`apksigner`, paquet, version, ABI) et publie `LocalDictate-vX.Y.Z.apk` + `SHA256SUMS`. La version vient du tag (code de version calculé). Le tag `android-v0.1.0` a été déplacé une fois de `93c3c14` à `d189e5a` parce que sa release avait échoué sans rien publier ; ne jamais déplacer un tag dont la release existe.

| Version | Contenu |
|---|---|
| 0.1.0 | Première dictée signée (insertion API33 stricte) |
| 0.1.1 | Raison du refus de cible affichée |
| 0.1.2 | Type de saisie : classe + variation (Keep) |
| 0.1.3 | Relecture de confirmation ; bulle libérée après succès |
| 0.1.4 | Raisons détaillées ; Copier ne masque plus le résultat |
| 0.1.5 | Confirmation par relecture malgré les événements |
| 0.1.6 | Copie automatique de secours ; historique des 5 derniers textes |
| 0.1.7 | Événements des autres applis ignorés |
| 0.1.8 | Refonte : décision unique à l’arrêt sur lectures fraîches, composition préservée, sélection jamais remplacée, bulle toujours prête. **Insertion confirmée par le mainteneur** (Keep, plusieurs dictées de suite, autre app) |
| 0.1.9 | Micro ouvert dès le toucher (Silero chargé en parallèle), segments élargis de 0,3 s, coupure forcée à 20 s au lieu de 8 s |
| 0.2.0 | Bulle façon Wispr Flow (carré au repos, pilule ✕/onde/✓, glisser sur ✕ pour masquer, retour au réveil) ; ✕ annule sans insérer mais garde le texte |

## Périmètre déjà validé par le mainteneur

- Français et anglais ; traitement local, sans cloud/API payante/analytics. Modèles préparés explicitement, jamais embarqués dans Git/APK.
- Dictée jusqu’à 15 min (usage réel visé : 5 à 10 min), sans arrêt sur silence : bulle, démarrage/arrêt utilisateur puis insertion au curseur ; overlay et accessibilité acceptés, Gboard conservé. Plusieurs dictées de suite, dans toutes les applis possibles.
- **Ne jamais perdre une dictée** : en cas d’échec, copie automatique dans le presse-papiers (accepté le 31/10/2026, même si elle remplace le clip précédent) et historique dans l’app.
- Mode distinct : réunion jusqu’à 2 h, enregistrement puis transcription et résumé local. Le résumé actuellement développé est **extractif**, à partir de phrases originales horodatées, pas un LLM génératif.
- le mainteneur télécharge les APK depuis les releases GitHub et teste lui-même sur son Pixel ; il renvoie des captures d’écran.

Voir [le périmètre](mvp.md), [l’architecture](architecture.md), [l’insertion](insertion.md), [la CI](ci.md) et [la recette Pixel](acceptation-pixel.md).

## Branches

- **`main`** : dictée complète publiée (voir releases).
- **`work/dictation`** : fusionnée dans `main` (commit `4cfe9c1`).
- **`work/meeting-core`** — noyau validé `fd84d93`, puis point d’intégration WIP `d4083376741af126f3492eebaf314addc027b10b` : stockage audio/journal/résumé extractif plus contrôleur, pipeline, dépôt, service et export commencés. Branchement UI/manifest/tests et transitions à terminer/revoir ; ne pas supposer cette branche compilable. Worktree VPS : `<private folder>`. À réconcilier avec `MicrophoneCapture`, les contrôleurs et l’UI de `main`, pas par « ours/theirs ».

## Points de revue toujours ouverts

1. Insertion : les risques restants listés dans `insertion.md` (composition antérieure non rapportée, champs web, éditeurs recréés, Android 29–32).
2. FGS microphone armé depuis Activity visible, micro fermé au repos, arrêt/révocation/mute/notification accessibles. Pas de service recréé silencieusement depuis une ancienne commande.
3. Réunion : un seul verrou d’usage partagé avec préparation/dictée ; audio app-private borné sur disque, capture d’abord puis ASR réel en flux, timestamps issus de l’audio. Ne pas perdre la transcription si résumé/export échoue.

## Validation locale

Avec SDK existant et JDK17 :

```sh
cd ~/dev/sources/localdictate
export ANDROID_HOME=/path/to/android-sdk
export TMPDIR=$TMPDIR
./android/gradlew -p android --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Environ 5 min par build complet. RAM/swap limitées : pas de builds/modèles en parallèle. Le mode cache Robolectric optionnel est documenté dans `docs/ci.md`.

La clé dédiée existe **hors Git/vault** sous `<private key folder>`, permissions 0700/0600 ; secrets `SIGNING_*` configurés sur ce dépôt. Ne pas recréer/réutiliser une autre clé et ne jamais afficher les valeurs. `<private folder> --version X.Y.Z` construit et inspecte un APK signé local (98 tests, inspection statique réussie le 30/10/2026) ; les livraisons à le mainteneur passent par les releases GitHub.

## Toujours non vérifié sur téléphone

Insertion multi-apps (seul Keep essayé), fiabilité de l’insertion v0.1.8, qualité anglaise, dictées de 5–15 min, maintien du FGS/micro, mode avion puis retour de connectivité, mémoire, latence, batterie et chauffe, Android 29–32. Aucun test hôte ne doit être présenté comme cette recette Pixel.

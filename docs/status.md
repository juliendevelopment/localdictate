# État du projet

## Jalon actuel : socle

Le périmètre contient deux modes : dictée FR/EN 15 minutes prioritaire et réunion 2 heures avec transcription/résumé local distincts. Le socle contient l’écran réel des modes, des règles de durée et un workflow de build ; aucune transcription ni synthèse n’est simulée.

## Vérification

- Local : `testDebugUnitTest lintDebug assembleDebug assembleRelease` exécutés avec succès.
- **11 tests**, dont 3 sur la vraie activité Compose/Robolectric : aucun échec, aucune erreur, aucun test ignoré.
- Lint sans erreur bloquante ; avertissements de versions/target SDK plus récents disponibles, sans mise à niveau aveugle des versions compatibles.
- APK debug signé par la clé de développement locale et APK release minifié non signé réellement produits.
- Manifest des APK inspecté : aucune permission Internet, microphone ou overlay dans le socle.
- Workflow distant : mêmes tests/builds sur chaque push `main`, avec validation du wrapper et artefacts APK/rapports. [État des exécutions](https://github.com/juliendevelopment/localdictate/actions/workflows/android-build.yml) ; la validation de livraison doit porter sur le SHA exact et sur l’artefact téléchargé.

Ces résultats ne valident pas encore une fonction vocale ou le fonctionnement sur le Pixel.

## Prochaine étape d’implémentation

Valider un vrai moteur ASR FR/EN compatible confidentialité/licence/Pixel, vérifier les API/artefacts natifs, puis ajouter la préparation explicite du modèle et une capture foreground bornée. Implémenter et tester ensuite la bulle et l’insertion sûre avant de passer au mode réunion.

## Non vérifié / non livré

- Microphone, modèles, transcription, overlay et service d’accessibilité.
- Insertion dans des applications réelles et conservation de leur texte.
- Session dictée 15 min / réunion 2 h, résumé, export et récupération après interruption.
- Mémoire, latence, consommation, température et trafic sur Pixel.
- Signature stable, APK de release et installation en mise à jour.

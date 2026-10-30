# LocalDictate

Application Android personnelle de reconnaissance vocale **locale**, en français et en anglais, sans abonnement, API payante, analytics ni copie de la marque/interface de Wispr Flow.

## Deux modes

- **Dictée** : bulle flottante, démarrage/arrêt explicites, jusqu’à **15 minutes**, puis insertion au curseur dans l’application cible, en conservant Gboard. Repli par copie explicite si le champ n’est pas compatible.
- **Réunion** : enregistrement explicite jusqu’à **2 heures**, transcription sauvegardée progressivement puis **résumé local**. Ce mode n’insère rien dans une autre application.

## État réel

Ce premier jalon est le **socle du projet**, pas une app de dictée terminée : écran Android présentant les deux modes, règles de durée testées, build reproductible et CI. Aucun microphone, modèle, service d’accessibilité ou résumé n’est simulé. La reconnaissance, la bulle, l’insertion et le traitement des réunions restent à implémenter.

Le socle ne demande aucune permission sensible et ne possède pas la permission Internet. Un téléchargement de modèles ne pourra être ajouté qu’en préparation explicite, pas pendant l’écoute.

## Développement

- Checkout VPS : `~/dev/sources/localdictate`
- Code Android : [`android/`](android/), Kotlin + Compose, JDK 17, SDK 35, minimum Android 10.
- Application : `be.arte.localdictate` (`.debug` pour les builds debug).
- [Périmètre et critères](docs/mvp.md) · [Architecture](docs/architecture.md) · [Build et CI](docs/ci.md) · [Références](docs/references.md) · [État et prochaine étape](docs/status.md).

```sh
cd android
./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleRelease
```

[GitHub Actions](https://github.com/juliendevelopment/localdictate/actions/workflows/android-build.yml) construit à chaque push sur `main`, sur les pull requests et à la demande. Le debug APK est un artefact de développement ; aucune release utilisateur ni signature stable n’est encore publiée.

Aucune licence de redistribution n’est choisie pour le code du projet à ce stade. Les dépendances conservent leurs propres licences.

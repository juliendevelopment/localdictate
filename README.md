# LocalDictate

Dictée Android personnelle **locale**, français/anglais, sans abonnement, API payante, analytics ni enregistrement automatique.

La dictée est raccordée à un vrai moteur Parakeet TDT 0.6B v3 via sherpa-onnx, avec préparation explicite vérifiée, bulle déplaçable, capture foreground, segmentation Silero, limite 15 min et récupération locale du texte. **Aucun essai sur téléphone n’est encore déclaré réussi.** Le mode réunion (2 h, transcription et résumé écrit locaux) est distinct et clairement indisponible.

## Utilisation

1. Préparer les modèles volontairement (environ 488 Mo de réseau ; 1,5 Go libres), puis autoriser microphone, notifications, bulle et accessibilité depuis l’écran de configuration.
2. Armer la bulle depuis cet écran visible : le service charge les modèles, le micro reste fermé.
3. Placer le curseur dans un champ d’une autre app, toucher **Dicter**, parler avec les pauses voulues, puis **Arrêter**. Le micro est libéré avant le traitement des derniers segments. Croix/notification : arrêter et désarmer.
4. Sur **Android 13 / API 33+**, l’insertion automatique utilise directement la connexion d’édition d’accessibilité : elle ne lit, n’écrit et n’efface jamais le presse-papiers et conserve Gboard comme clavier. La cible, la sélection, la session d’éditeur et le contexte complet doivent être inchangés et sans composition en cours. Sinon, ainsi que sur Android 10–12, ouvrir LocalDictate, **Copier**, puis coller soi-même. Cette seule action explicite remplace le clip précédent et le marque sensible (indication de masquage, pas protection d’accès). Toujours vérifier le champ avant de recopier après une insertion non confirmée.
5. Le texte reconnu reste dans le stockage privé jusqu’à **Effacer**. Après un arrêt brutal, le texte synchronisé est récupéré, mais le son encore en attente est perdu. Aucun collage ni réarmement automatique après reprise.

Aucun poids modèle dans le dépôt ou l’APK. Internet sert seulement à la préparation ; elle est bloquée tant que le service est armé ou termine son travail. L’accessibilité consulte uniquement la cible demandée et la revalide, sans historique d’écran ni lecture permanente. Pas de champ password, pas de remplacement complet du texte d’un champ.

## Développement et preuves

Kotlin/Compose dans `android/`, JDK 17, SDK 35, min 29, ABI arm64-v8a. Wrapper et dépendances épinglés ; le build peut télécharger seulement l’AAR natif épinglé, jamais les poids. Aucune licence ajoutée au code propre du projet.

```sh
cd android
ANDROID_HOME=/chemin/du/sdk ./gradlew --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
```

[État exact et résultats](docs/status.md) · [Architecture et insertion directe](docs/architecture.md) · [Recette Pixel](docs/acceptation-pixel.md) · [Périmètre](docs/mvp.md) · [Licences et attribution NVIDIA](docs/licences.md) · [Build/CI](docs/ci.md).

[GitHub Actions](https://github.com/juliendevelopment/localdictate/actions/workflows/android-build.yml) conserve ses déclencheurs et permissions. Le debug est un artefact de développement ; release locale minifiée non signée. l’agent possède revue, Git, signature et livraison ; aucun commit/publication dans ce lot.

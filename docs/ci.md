# Build et intégration continue

## Conventions

- Kotlin 2.1.0, Compose BOM 2025.01.01, AGP 8.13.2, Gradle 9.0.0, JDK 17 : base compatible reprise des conventions de un projet voisin, pas de copie de son code métier ni de ses secrets.
- Android compile/target 35 et build-tools 35.0.0 ; minimum 29.
- Wrapper Gradle avec SHA-256 de distribution ; son JAR copié a été comparé au digest publié par Gradle : `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3`.
- Actions GitHub épinglées à un commit résolu de leurs tags officiels. Les PR disposent uniquement de `contents: read` ; aucun secret de signature n’est configuré/utilisé.

## Local

```sh
cd android
./gradlew --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Configurer `ANDROID_HOME` vers le SDK existant ou créer son propre `local.properties` ignoré par Git. Le pin Java asdf se trouve dans `android/.tool-versions`. Pas d’Android Studio ni d’émulateur requis pour ces vérifications.

## GitHub Actions

`.github/workflows/android-build.yml` s’exécute sur **tout push sur main**, sur pull request et manuellement. Il valide le wrapper, installe le SDK explicitement, lance tests/lint/builds puis publie :

- `localdictate-debug-<SHA>` : APK debug et SHA256SUMS, pour développement seulement.
- `localdictate-reports-<SHA>` : résultats JUnit/lint disponibles même en cas d’échec du build.

`assembleRelease` exerce R8 et produit un APK **non signé**, non annoncé comme installable. Les APK debug des exécutions CI peuvent avoir des certificats différents : ils ne constituent pas un canal de mise à jour stable. Avant la première vraie livraison, créer une clé dédiée hors checkout, configurer les secrets `SIGNING_*`, vérifier certificat/checksum/package, puis ajouter un workflow de release taguée. Aucune release GitHub n’est prétendue livrée par le build du socle.

## Vérification des jalons

Vérifier la CI pour le SHA exact, le dépôt privé et la branche distante `main`. Télécharger l’artefact réel et vérifier son checksum/manifest. Compter les tests depuis les XML JUnit, pas à partir d’une estimation du terminal. Les tests JVM/Compose ne valident pas le microphone, le JNI, l’insertion multi-app, le mode avion ou les durées réelles sur Pixel.

## Exécution dans un HOME en lecture seule

Le résolveur Maven de Robolectric essaie de créer `~/.robolectric-download-lock`. Un environnement restreint peut utiliser un SDK Robolectric déjà en cache, sans écrire dans HOME, en passant `-ProbolectricDependencyDir=/chemin/du/dossier/contenant/android-all-instrumented-15-robolectric-12650502-i7.jar`. Cette option configure uniquement le worker de test en mode offline. Elle ne désactive aucun test et n’installe/modifie aucune configuration globale.

Le build résout l’AAR sherpa 2.0.1 via `fetchSherpa`/`builtBy` (SHA/taille épinglés). Aucun poids ASR/VAD n’est téléchargé par Gradle. Les APK ne contiennent que l’ABI arm64-v8a et les classes JNI sont conservées en release. Il n’y a pas de dépendance ONNX Runtime Java, de secret ou de configuration de signature dans cette lane. Les déclencheurs/permissions des workflows ne sont pas modifiés.

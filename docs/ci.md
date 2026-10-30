# Build et intégration continue

## Conventions

- Kotlin 2.1.0, Compose BOM 2025.01.01, AGP 8.13.2, Gradle 9.0.0, JDK 17 : base compatible reprise des conventions de un projet voisin, pas de copie de son code métier ni de ses secrets.
- Android compile/target 35 et build-tools 35.0.0 ; minimum 29.
- Wrapper Gradle avec SHA-256 de distribution ; son JAR copié a été comparé au digest publié par Gradle : `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3`.
- Actions GitHub épinglées à un commit résolu de leurs tags officiels. Les PR et le workflow de build disposent uniquement de `contents: read` et ne reçoivent aucun secret de signature. Le workflow séparé `Android Release` ne reçoit les secrets que sur un tag de release de confiance.

## Local

```sh
cd android
./gradlew --no-daemon --max-workers=2 --console=plain testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Configurer `ANDROID_HOME` vers le SDK existant ou créer son propre `local.properties` ignoré par Git. Le pin Java asdf se trouve dans `android/.tool-versions`. Pas d’Android Studio ni d’émulateur requis pour ces vérifications.

## GitHub Actions

`.github/workflows/android-build.yml` s’exécute sur **tout push sur main**, sur pull request et manuellement. Il valide le wrapper, installe le SDK explicitement, lance tests/lint/builds puis publie :

- `localdictate-debug-<SHA>` : APK debug et SHA256SUMS, pour développement seulement.
- `localdictate-reports-<SHA>` : résultats JUnit/lint disponibles même en cas d’échec du build.

`assembleRelease` exerce R8 et produit pour l’instant un APK **non signé**, non annoncé comme installable. Les APK debug des exécutions CI peuvent avoir des certificats différents : ils ne constituent pas un canal de mise à jour stable.

Une clé de release dédiée a été créée hors checkout/vault (`<private key folder>`, répertoire 0700/fichiers 0600), et les quatre secrets `SIGNING_*` ont été configurés sur ce dépôt uniquement. Le workflow `android-release.yml` est préparé : tag strict `android-vMAJOR.MINOR.PATCH`, calcul de version testé, commit appartenant à `main` avec build vert au SHA exact, secrets obligatoires, vérification `apksigner` et refus de remplacer une release existante. L’intégration Gradle/signature doit encore être exercée avec la dictée avant le premier tag. **Aucune release utilisateur n’est encore publiée.**

## Vérification des jalons

Vérifier la CI pour le SHA exact, le dépôt privé et la branche distante `main`. Télécharger l’artefact réel et vérifier son checksum/manifest. Compter les tests depuis les XML JUnit, pas à partir d’une estimation du terminal. Les tests JVM/Compose ne valident pas le microphone, le JNI, l’insertion multi-app, le mode avion ou les durées réelles sur Pixel.
